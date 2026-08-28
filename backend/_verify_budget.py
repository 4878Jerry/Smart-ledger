"""验证预算云同步后端接口：GET/PUT /api/users/budget + 迁移生效（仅标准库）。"""
import json
import sqlite3
import sys
import urllib.request

BASE = "http://localhost:8000"


def call(method: str, path: str, token: str | None = None, body: dict | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def main() -> int:
    # 1. 登录 test1 获取 token
    status, login = call("POST", "/api/login", body={"username": "test1", "password": "123456"})
    assert status == 200 and login.get("code") == 0, f"登录失败: {status} {login}"
    token = login["data"]["token"]
    print("[OK] 登录 test1")

    # 2. 初始 GET 预算（应为空）
    status, body = call("GET", "/api/users/budget", token)
    assert status == 200 and body.get("code") == 0, f"GET 预算失败: {status} {body}"
    assert body["data"]["budget"] == {}, f"初始预算应为空: {body}"
    print("[OK] GET 初始预算为空:", body["data"]["budget"])

    # 3. PUT 预算
    budget = {"餐饮": 1200, "交通": 300.5, "购物": 800}
    status, body = call("PUT", "/api/users/budget", token, {"budget": budget})
    assert status == 200 and body.get("code") == 0, f"PUT 预算失败: {status} {body}"
    print("[OK] PUT 预算:", body["data"]["budget"])

    # 4. 回读验证
    status, body = call("GET", "/api/users/budget", token)
    got = body["data"]["budget"]
    assert got == budget, f"回读不一致: {got} != {budget}"
    print("[OK] GET 回读一致:", got)

    # 5. 无 token 应 401
    status, _ = call("GET", "/api/users/budget")
    assert status == 401, f"无 token 应 401, 实际 {status}"
    print("[OK] 无 token 访问返回 401")

    # 6. 清理：恢复空预算（避免污染 test1 数据）
    status, body = call("PUT", "/api/users/budget", token, {"budget": {}})
    assert status == 200 and body.get("code") == 0
    print("[OK] 已清理 test1 预算")

    # 7. 迁移生效检查：users 表存在 budget_json 列
    conn = sqlite3.connect("smartbutler.db")
    cols = [row[1] for row in conn.execute("PRAGMA table_info(users)")]
    assert "budget_json" in cols, f"users 表缺少 budget_json 列: {cols}"
    conn.close()
    print("[OK] 迁移生效: users.budget_json 列存在")

    print("\n全部验证通过")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as e:
        print(f"[FAIL] {e}")
        sys.exit(1)
