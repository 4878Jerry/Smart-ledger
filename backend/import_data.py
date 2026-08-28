# -*- coding: utf-8 -*-
"""
import_data.py — 将 C++ 桌面版测试数据（20260823090311/expense_data.json）导入 smartbutler.db。

字段映射：
    date     → timestamp（毫秒时间戳，按本地时区解析 YYYY-MM-DD）
    type     → type（收入 / 支出）
    category → category
    amount   → amount
    payee    → note（后端 transactions 表无 payee 字段，商户名写入 note）
    （JSON 原 note 字段无对应目标列，忽略）

按用户分配：
    data[:50]  → test1（前 50 条）
    data[50:]  → test2（后 52 条）

其他：
    is_public ：随机 50% true / false（可用 --seed 固定随机数，保证可复现）
    local_id  ：随机 UUID（与 Android 端格式一致，同步幂等用）
    synced_at ：当前时间（视为已同步）
    test1 / test2 用户不存在时自动创建（密码 123456，与 add_test_users.py 一致）
    test1 / test2 名下已有记录时先清空再导入（不影响其他用户数据）

用法（在 backend/ 目录下）：
    python import_data.py                    # 默认读取 ../20260823090311/expense_data.json
    python import_data.py <json路径>          # 指定数据文件
    python import_data.py --seed 42           # 固定 is_public 随机分布（可复现）
"""
import json
import os
import random
import sqlite3
import sys
import uuid
from datetime import datetime

BACKEND_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BACKEND_DIR, "smartbutler.db")
DEFAULT_DATA_PATH = os.path.join(
    os.path.dirname(BACKEND_DIR), "20260823090311", "expense_data.json"
)

TEST_USERS = [
    ("test1", "123456", "测试用户1"),
    ("test2", "123456", "测试用户2"),
]


def hash_password(password: str) -> str:
    """与后端 auth.hash_password 一致：bcrypt 仅使用前 72 字节"""
    import bcrypt
    return bcrypt.hashpw(password.encode("utf-8")[:72], bcrypt.gensalt()).decode("utf-8")


def ensure_users(conn: sqlite3.Connection) -> dict[str, int]:
    """确保 test1 / test2 存在，返回 {用户名: 用户id}。不存在则自动创建。"""
    user_ids = {}
    for username, password, nickname in TEST_USERS:
        row = conn.execute(
            "SELECT id FROM users WHERE username = ?", (username,)
        ).fetchone()
        if row:
            user_ids[username] = row[0]
            print(f"[用户] {username}（id={row[0]}）已存在")
            continue
        try:
            hashed = hash_password(password)
        except ImportError:
            print(
                f"[错误] 用户 {username} 不存在且无法自动创建：缺少 bcrypt 库。\n"
                "        请先运行 python add_test_users.py 创建测试账号，"
                "或在 venv 环境中运行本脚本。"
            )
            sys.exit(1)
        cur = conn.execute(
            "INSERT INTO users (username, password_hash, nickname, created_at) "
            "VALUES (?, ?, ?, datetime('now', 'localtime'))",
            (username, hashed, nickname),
        )
        user_ids[username] = cur.lastrowid
        print(f"[用户] 已自动创建 {username}（id={cur.lastrowid}，密码 {password}）")
    return user_ids


def date_to_timestamp(date_str: str) -> int:
    """YYYY-MM-DD → 毫秒时间戳（本地时区，与 Android 端日期显示一致）"""
    return int(datetime.strptime(date_str, "%Y-%m-%d").timestamp() * 1000)


def load_records(data_path: str) -> list[dict]:
    with open(data_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list) or not data:
        print(f"[错误] 数据文件格式不正确或为空: {data_path}")
        sys.exit(1)
    return data


def parse_args(argv: list[str]) -> tuple[list[str], int | None]:
    """解析命令行：位置参数为数据文件路径，--seed <int> 固定随机分布"""
    positional, seed = [], None
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--seed":
            if i + 1 >= len(argv):
                print("[错误] --seed 需要一个整数参数，例如 --seed 42")
                sys.exit(1)
            seed = int(argv[i + 1])
            i += 2
            continue
        positional.append(arg)
        i += 1
    return positional, seed


def main() -> int:
    positional, seed = parse_args(sys.argv[1:])
    data_path = positional[0] if positional else DEFAULT_DATA_PATH

    if not os.path.exists(DB_PATH):
        print(f"[错误] 数据库文件不存在: {DB_PATH}")
        print("请先启动后端（run.bat 或 uvicorn main:app）让建表完成后再运行本脚本。")
        return 1
    if not os.path.exists(data_path):
        print(f"[错误] 数据文件不存在: {data_path}")
        return 1

    records = load_records(data_path)
    total = len(records)
    print(f"[读取] {data_path}，共 {total} 条记录")

    conn = sqlite3.connect(DB_PATH)
    try:
        # 确认 transactions 表存在
        table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='transactions'"
        ).fetchone()
        if not table:
            print("[错误] transactions 表不存在，请先启动后端建表。")
            return 1

        user_ids = ensure_users(conn)
        test1_id, test2_id = user_ids["test1"], user_ids["test2"]

        # 清空 test1 / test2 名下旧数据（若存在）
        deleted = conn.execute(
            "DELETE FROM transactions WHERE user_id IN (?, ?)",
            (test1_id, test2_id),
        ).rowcount
        if deleted:
            print(f"[清空] 已删除 test1/test2 名下旧记录 {deleted} 条")
        else:
            print("[清空] test1/test2 名下无旧记录，直接导入")

        # is_public 随机分布（--seed 可固定，保证可复现）
        rng = random.Random(seed)
        print(f"[随机] is_public 分布种子: {'系统随机' if seed is None else seed}")

        now = str(datetime.now())  # 与 SQLAlchemy DateTime 存储格式一致（空格分隔），避免 3.12 弃用警告
        rows = []
        for idx, item in enumerate(records):
            # 前 50 条 → test1，后 52 条 → test2
            user_id = test1_id if idx < 50 else test2_id
            rows.append((
                user_id,
                str(uuid.uuid4()),                      # local_id（同步幂等用）
                float(item["amount"]),
                str(item["category"]),
                str(item["type"]),
                str(item["payee"]),                     # payee → note
                date_to_timestamp(item["date"]),
                rng.random() < 0.5,                     # is_public 随机 50%
                now,                                    # synced_at（已同步）
            ))

        conn.executemany(
            "INSERT INTO transactions "
            "(user_id, local_id, amount, category, type, note, timestamp, is_public, synced_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        conn.commit()

        # 统计输出
        print(f"[导入] 成功写入 {len(rows)} 条")
        for name, uid, lo, hi in (
            ("test1", test1_id, 0, 50),
            ("test2", test2_id, 50, total),
        ):
            n = conn.execute(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ?", (uid,)
            ).fetchone()[0]
            pub = conn.execute(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND is_public = 1",
                (uid,),
            ).fetchone()[0]
            dates = conn.execute(
                "SELECT MIN(date(timestamp/1000, 'unixepoch', 'localtime')), "
                "       MAX(date(timestamp/1000, 'unixepoch', 'localtime')) "
                "FROM transactions WHERE user_id = ?",
                (uid,),
            ).fetchone()
            print(
                f"  - {name}: {n} 条（公开 {pub} 条），"
                f"日期范围 {dates[0]} ~ {dates[1]}"
            )
        return 0
    except sqlite3.Error as e:
        conn.rollback()
        print(f"[错误] 数据库操作失败: {e}")
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
