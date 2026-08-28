"""智能管家（SmartButler）Android 应用后端服务入口

启动：uvicorn main:app --host 0.0.0.0 --port 8000 --reload
文档：http://localhost:8000/docs（Swagger 自动生成）
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import select, text

import models  # noqa: F401  确保建表前所有模型已注册
from auth import hash_password
from database import AsyncSessionLocal, Base, engine
from routers import auth as auth_router
from routers import community as community_router
from routers import transactions as transactions_router


async def create_tables() -> None:
    """首次启动自动创建全部数据表"""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


async def migrate_posts_updated_at() -> None:
    """老库 posts 表无 updated_at 列：ALTER 添加并回填为各自 created_at（视为从未更新过）"""
    async with engine.begin() as conn:
        cols = (await conn.execute(text("PRAGMA table_info(posts)"))).fetchall()
        if "updated_at" not in {row[1] for row in cols}:
            await conn.execute(text("ALTER TABLE posts ADD COLUMN updated_at DATETIME"))
            await conn.execute(text("UPDATE posts SET updated_at = created_at WHERE updated_at IS NULL"))


async def migrate_posts_budget_breakdown() -> None:
    """老库 posts 表无 budget_breakdown 列：ALTER 添加并回填为空对象（表示没有预算方案）"""
    async with engine.begin() as conn:
        cols = (await conn.execute(text("PRAGMA table_info(posts)"))).fetchall()
        if "budget_breakdown" not in {row[1] for row in cols}:
            await conn.execute(text("ALTER TABLE posts ADD COLUMN budget_breakdown JSON"))
            await conn.execute(text("UPDATE posts SET budget_breakdown = '{}' WHERE budget_breakdown IS NULL"))


async def migrate_posts_visibility() -> None:
    """老库 posts 表无可见度字段：逐列 ALTER 添加，默认 'public'（历史帖子视为公开）"""
    async with engine.begin() as conn:
        cols = (await conn.execute(text("PRAGMA table_info(posts)"))).fetchall()
        existing = {row[1] for row in cols}
        for col in ("visibility", "data_visibility", "budget_visibility"):
            if col not in existing:
                await conn.execute(
                    text(f"ALTER TABLE posts ADD COLUMN {col} VARCHAR(20) DEFAULT 'public'")
                )


async def migrate_users_is_data_public() -> None:
    """老库 users 表无 is_data_public 列：ALTER 添加，默认 1（公开）"""
    async with engine.begin() as conn:
        cols = (await conn.execute(text("PRAGMA table_info(users)"))).fetchall()
        if "is_data_public" not in {row[1] for row in cols}:
            await conn.execute(
                text("ALTER TABLE users ADD COLUMN is_data_public INTEGER DEFAULT 1 NOT NULL")
            )


async def migrate_users_budget_json() -> None:
    """老库 users 表无 budget_json 列：ALTER 添加，默认 '{}'（分类 → 金额）"""
    async with engine.begin() as conn:
        cols = (await conn.execute(text("PRAGMA table_info(users)"))).fetchall()
        if "budget_json" not in {row[1] for row in cols}:
            await conn.execute(
                text("ALTER TABLE users ADD COLUMN budget_json TEXT DEFAULT '{}' NOT NULL")
            )


async def seed_users() -> None:
    """预置测试账号 test1 / test2（密码均为 123456），已存在则跳过"""
    async with AsyncSessionLocal() as db:
        for name in ("test1", "test2"):
            exists = (await db.execute(
                select(models.User).where(models.User.username == name)
            )).scalar_one_or_none()
            if exists is None:
                db.add(models.User(
                    username=name,
                    password_hash=hash_password("123456"),
                    nickname=name,
                ))
        await db.commit()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await create_tables()
    await migrate_posts_updated_at()
    await migrate_posts_budget_breakdown()
    await migrate_posts_visibility()
    await migrate_users_is_data_public()
    await migrate_users_budget_json()
    await seed_users()
    yield


app = FastAPI(
    title="SmartButler API",
    description="智能管家 Android 应用后端服务（本地 / Tailscale 部署）",
    version="1.0.0",
    lifespan=lifespan,
)

# 跨域：允许所有来源，方便手机 / 网页调试
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    """统一错误返回格式：{"code": 非0, "data": null, "msg": 错误信息}"""
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.status_code, "data": None, "msg": exc.detail},
    )


app.include_router(auth_router.router)
app.include_router(transactions_router.router)
app.include_router(community_router.router)


@app.get("/", tags=["health"])
async def health():
    """健康检查"""
    return {"code": 0, "data": {"service": "smartbutler-backend", "docs": "/docs"}, "msg": "success"}
