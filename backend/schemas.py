"""Pydantic 请求 / 响应模型，统一返回格式 {code, data, msg}"""
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


def ok(data: Any = None, msg: str = "success") -> dict:
    """统一成功返回体：{"code": 0, "data": ..., "msg": "success"}"""
    return {"code": 0, "data": data, "msg": msg}


class ApiResponse(BaseModel, Generic[T]):
    """统一响应包装（用于 /docs 文档展示）"""
    code: int = 0
    data: T | None = None
    msg: str = "success"


# ---------- 认证 ----------

class RegisterRequest(BaseModel):
    username: str
    password: str
    nickname: str | None = None


class LoginRequest(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    nickname: str | None
    # 用户级全局数据公开开关：1=公开，0=不公开
    is_data_public: int = 1
    created_at: Any


class UserSettingsRequest(BaseModel):
    """用户设置更新：目前仅「数据公开」全局开关"""
    is_data_public: int = Field(ge=0, le=1, description="1=公开，0=不公开")


class BudgetUpdate(BaseModel):
    """用户预算整体更新：分类 → 金额（如 {"餐饮": 1200, "交通": 300}）"""
    budget: dict[str, float] = Field(default_factory=dict, description="预算方案（分类 → 月预算金额）")


class LoginData(BaseModel):
    token: str
    user: UserOut


# ---------- 交易记录 ----------

class TransactionPayload(BaseModel):
    """Android 端上传的单条记录（localId 为本地 UUID，用于增量同步幂等）"""
    localId: str = ""
    amount: float
    category: str
    type: str = "支出"
    note: str = ""
    timestamp: int = Field(description="毫秒时间戳")
    is_public: bool = False


class SyncRequest(BaseModel):
    transactions: list[TransactionPayload]


class TransactionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    # Android 端本地 UUID：下载时用于本地去重（重装 App 后恢复记录不重复）
    local_id: str | None = None
    amount: float
    category: str
    type: str
    note: str
    timestamp: int
    is_public: bool


# ---------- 社区 ----------

class CommentItem(BaseModel):
    """客户端回传的评论（用于帖子已存在时同步评论，不影响 created_at）"""
    id: int | None = None
    username: str = ""
    content: str
    created_at: str | None = None


class PostCreate(BaseModel):
    month: str = Field(pattern=r"^\d{4}-\d{2}$", description="月份，格式 YYYY-MM")
    total_expense: float = 0
    # 消费数据模块：为空表示本帖子不包含消费数据（只发预算方案）
    category_breakdown: dict[str, float] = Field(default_factory=dict)
    # 预算方案模块：可为空（不发布预算）
    budget_breakdown: dict[str, float] | None = None
    top_category: str = ""
    saving_tip: str = ""
    # 帖子级可见度：public 公开 / private 仅自己可见（默认 public）
    visibility: str = "public"
    # 模块级可见度：消费数据 / 预算方案 各自独立控制（默认 public）
    data_visibility: str = "public"
    budget_visibility: str = "public"
    # 以下字段可选：帖子已存在时仅更新这些，不更新 created_at
    likes: int | None = None
    comments: list[CommentItem] | None = None
    # 客户端毫秒时间戳：首次创建帖子时优先使用（不传则用服务器当前时间）
    timestamp: int | None = Field(default=None, description="客户端毫秒时间戳，首次创建时优先使用")


class PostUpdate(BaseModel):
    """帖子可见度更新（PUT /api/posts/{id}）：帖子级 + 两个模块级均可选，不传则保持原值"""
    visibility: str | None = None
    data_visibility: str | None = None
    budget_visibility: str | None = None


class CommentCreate(BaseModel):
    post_id: int
    content: str
