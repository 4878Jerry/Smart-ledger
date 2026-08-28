"""SQLite 表结构（SQLAlchemy ORM 定义）"""
from datetime import datetime

from sqlalchemy import (JSON, BigInteger, Boolean, DateTime, Float, ForeignKey,
                        Integer, String, Text, UniqueConstraint)
from sqlalchemy.orm import Mapped, mapped_column

from database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    nickname: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # 用户级全局数据公开开关：1=公开（默认），0=不公开（其所有帖子不出现在公开流）
    is_data_public: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    # 用户预算方案 JSON（{"餐饮": 800, "交通": 200}），重装 App 后登录恢复；老库回填见 main.py 迁移
    budget_json: Mapped[str] = mapped_column(Text, default="{}", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class Transaction(Base):
    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    # Android 端本地 UUID，用于增量同步幂等（已同步过的记录跳过）
    local_id: Mapped[str | None] = mapped_column(String(64), unique=True, nullable=True)
    amount: Mapped[float] = mapped_column(Float, nullable=False)
    category: Mapped[str] = mapped_column(String(64), nullable=False)
    type: Mapped[str] = mapped_column(String(8), nullable=False)  # 收入 / 支出
    note: Mapped[str] = mapped_column(Text, default="")
    timestamp: Mapped[int] = mapped_column(BigInteger, nullable=False)  # 毫秒时间戳
    is_public: Mapped[bool] = mapped_column(Boolean, default=False)
    synced_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)  # 同步时间，默认 NULL


class Post(Base):
    __tablename__ = "posts"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    month: Mapped[str] = mapped_column(String(7), nullable=False)  # 格式 YYYY-MM
    total_expense: Mapped[float] = mapped_column(Float, nullable=False)
    category_breakdown: Mapped[dict] = mapped_column(JSON, default=dict)  # {"餐饮": 500, ...}
    budget_breakdown: Mapped[dict] = mapped_column(
        JSON, default=dict, nullable=True
    )  # {"餐饮": 1000, ...} 预算方案，可为空；老库回填见 main.py 迁移
    top_category: Mapped[str] = mapped_column(String(64), default="")
    saving_tip: Mapped[str] = mapped_column(Text, default="")
    likes: Mapped[int] = mapped_column(default=0)
    # 帖子级可见度：public 公开 / private 仅自己可见；模块级（data/budget）见下方两字段
    visibility: Mapped[str] = mapped_column(String(20), default="public")
    # 消费数据模块可见度：public / private（独立于帖子级，帖子公开时仍受其控制）
    data_visibility: Mapped[str] = mapped_column(String(20), default="public")
    # 预算方案模块可见度：public / private（独立于帖子级，帖子公开时仍受其控制）
    budget_visibility: Mapped[str] = mapped_column(String(20), default="public")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    updated_at: Mapped[datetime | None] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )  # 最近更新时间：首次创建=created_at，更新同月帖子时刷新；老数据回填见 main.py 迁移


class Comment(Base):
    __tablename__ = "comments"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id"), index=True, nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class Like(Base):
    __tablename__ = "likes"
    __table_args__ = (UniqueConstraint("post_id", "user_id", name="uq_like_post_user"),)

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id"), index=True, nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
