"""JWT 生成与验证、密码哈希（bcrypt）、当前用户依赖注入"""
from datetime import datetime, timedelta, timezone

import bcrypt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from sqlalchemy.ext.asyncio import AsyncSession

from database import get_db
from models import User

# 生产环境请通过环境变量覆盖 SECRET_KEY
SECRET_KEY = "smartbutler-dev-secret-key-change-me-in-production"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = 7  # token 有效期 7 天

bearer_scheme = HTTPBearer(auto_error=False)


def _norm_password(password: str) -> bytes:
    """bcrypt 仅使用密码前 72 字节，超长部分截断（与 bcrypt 算法语义一致）"""
    return password.encode("utf-8")[:72]


def hash_password(password: str) -> str:
    """bcrypt 哈希存储密码，返回 "$2b$12$..." 格式"""
    return bcrypt.hashpw(_norm_password(password), bcrypt.gensalt()).decode("utf-8")


def verify_password(plain: str, hashed: str) -> bool:
    """校验明文密码与哈希是否匹配"""
    try:
        return bcrypt.checkpw(_norm_password(plain), hashed.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def create_access_token(user_id: int) -> str:
    """签发 JWT，过期时间 7 天"""
    expire = datetime.now(timezone.utc) + timedelta(days=ACCESS_TOKEN_EXPIRE_DAYS)
    payload = {"sub": str(user_id), "exp": expire}
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    """从 Authorization: Bearer {token} 解析当前登录用户，无效则抛 401"""
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="未登录或 token 缺失")
    try:
        payload = jwt.decode(credentials.credentials, SECRET_KEY, algorithms=[ALGORITHM])
        user_id = int(payload.get("sub", ""))
    except (JWTError, ValueError):
        raise HTTPException(status_code=401, detail="token 无效或已过期")
    user = await db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=401, detail="用户不存在")
    return user
