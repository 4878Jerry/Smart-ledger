# -*- coding: utf-8 -*-
"""
add_test_users.py — 向 smartbutler.db 添加测试账号 test1 / test2（密码 123456）。

用法（在 backend/ 目录下）:
    python add_test_users.py

说明:
    - 直接连接 backend/smartbutler.db（SQLite）
    - 密码使用 bcrypt 哈希存储，与服务器 auth.hash_password 完全一致
    - 已存在的账号跳过，不重复插入
"""
import os
import sqlite3
import sys

import bcrypt

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "smartbutler.db")

# (用户名, 密码, 昵称)
TEST_USERS = [
    ("test1", "123456", "测试用户1"),
    ("test2", "123456", "测试用户2"),
]


def hash_password(password: str) -> str:
    """与后端 auth.hash_password 一致：bcrypt 仅使用前 72 字节"""
    return bcrypt.hashpw(password.encode("utf-8")[:72], bcrypt.gensalt()).decode("utf-8")


def main() -> int:
    if not os.path.exists(DB_PATH):
        print(f"[错误] 数据库文件不存在: {DB_PATH}")
        print("请先启动后端（run.bat 或 uvicorn main:app）让建表完成后再运行本脚本。")
        return 1

    conn = sqlite3.connect(DB_PATH)
    try:
        created, skipped = [], []
        for username, password, nickname in TEST_USERS:
            exists = conn.execute(
                "SELECT 1 FROM users WHERE username = ?", (username,)
            ).fetchone()
            if exists:
                skipped.append(username)
                print(f"[跳过] 账号 {username} 已存在")
                continue
            conn.execute(
                "INSERT INTO users (username, password_hash, nickname, created_at) "
                "VALUES (?, ?, ?, datetime('now', 'localtime'))",
                (username, hash_password(password), nickname),
            )
            created.append(username)
            print(f"[创建] 账号 {username}（密码 {password}）")

        conn.commit()

        if created and skipped:
            print(f"已创建 {', '.join(created)}，{', '.join(skipped)} 已存在，跳过")
        elif created:
            print("已创建 test1 和 test2")
        else:
            print("已存在，跳过")
        return 0
    except sqlite3.Error as e:
        print(f"[错误] 数据库操作失败: {e}")
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
