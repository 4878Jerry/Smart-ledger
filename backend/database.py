"""数据库连接与会话管理（SQLite + aiosqlite 异步驱动）"""
from typing import AsyncIterator

from sqlalchemy import event
from sqlalchemy.engine import Engine
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

# SQLite 数据库文件生成在本目录下（smartbutler.db）
DATABASE_URL = "sqlite+aiosqlite:///./smartbutler.db"


class Base(DeclarativeBase):
    """所有 ORM 模型的基类"""


# 启用 SQLite 外键约束
@event.listens_for(Engine, "connect")
def _set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()


engine = create_async_engine(DATABASE_URL, echo=False)

AsyncSessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_db() -> AsyncIterator[AsyncSession]:
    """FastAPI 依赖：每个请求提供独立数据库会话"""
    async with AsyncSessionLocal() as session:
        yield session
