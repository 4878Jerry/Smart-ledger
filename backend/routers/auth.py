"""认证与账号接口：注册 / 登录 / 用户设置 / 预算"""
import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from auth import create_access_token, get_current_user, hash_password, verify_password
from database import get_db
from models import User
from schemas import BudgetUpdate, LoginRequest, RegisterRequest, UserOut, UserSettingsRequest, ok

router = APIRouter(prefix="/api", tags=["auth"])


@router.post("/register", response_model=None)
async def register(body: RegisterRequest, db: AsyncSession = Depends(get_db)):
    """注册新账号"""
    username = body.username.strip()
    if not username:
        raise HTTPException(status_code=400, detail="用户名不能为空")
    if len(body.password) < 6:
        raise HTTPException(status_code=400, detail="密码至少 6 位")

    exists = (await db.execute(select(User).where(User.username == username))).scalar_one_or_none()
    if exists:
        raise HTTPException(status_code=400, detail="用户名已存在")

    user = User(
        username=username,
        password_hash=hash_password(body.password),
        nickname=(body.nickname or "").strip() or username,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return ok(UserOut.model_validate(user))


@router.post("/login", response_model=None)
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    """登录，返回 JWT token 与用户信息（有效期 7 天）"""
    username = body.username.strip()
    user = (await db.execute(select(User).where(User.username == username))).scalar_one_or_none()
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=400, detail="用户名或密码错误")

    token = create_access_token(user.id)
    return ok({"token": token, "user": UserOut.model_validate(user)})


@router.put("/users/settings", response_model=None)
async def update_user_settings(
    body: UserSettingsRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """更新当前用户设置（需 JWT token）。

    目前仅支持全局「数据公开」开关：
    - is_data_public = 1：开启，公开帖子出现在公开流；
    - is_data_public = 0：关闭，该用户所有帖子不再出现在公开流（GET /api/stats/public 过滤）。
    返回更新后的用户信息。
    """
    user.is_data_public = body.is_data_public
    await db.commit()
    await db.refresh(user)
    return ok(UserOut.model_validate(user))


@router.get("/users/budget", response_model=None)
async def get_user_budget(user: User = Depends(get_current_user)):
    """获取当前用户预算（需 JWT token）：返回 {"budget": {分类: 金额}}"""
    budget = json.loads(user.budget_json or "{}")
    return ok({"budget": budget})


@router.put("/users/budget", response_model=None)
async def update_user_budget(
    body: BudgetUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """整体覆盖保存当前用户预算（需 JWT token）：写入 users.budget_json"""
    user.budget_json = json.dumps(body.budget, ensure_ascii=False)
    await db.commit()
    return ok({"budget": body.budget})
