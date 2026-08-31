"""社区接口：公开统计、发布统计、评论、点赞"""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from auth import get_current_user, get_current_user_optional
from database import get_db
from models import Comment, Like, Post, User
from schemas import CommentCreate, CommentItem, PostCreate, PostUpdate, ok

router = APIRouter(prefix="/api", tags=["community"])


def _norm_visibility(value: str | None) -> str:
    """可见度规范化：仅接受 public / private，其余回退为 public"""
    return value if value in ("public", "private") else "public"


async def _post_to_dict(
    p: Post,
    db: AsyncSession,
    mask_private_modules: bool = False,
    viewer_user_id: int | None = None,
) -> dict:
    """帖子 ORM → 统一响应 dict（public_stats 与 my_posts 共用）。

    mask_private_modules=True（公开流）时：模块级可见度为 private 的模块返回空对象 {},
    帖子本身仍可见；False（我的帖子，本人查看）返回完整数据以便编辑。
    viewer_user_id 提供时附带 liked（该用户是否已赞），作为客户端点赞状态的权威来源。
    """
    author = await db.get(User, p.user_id)
    comments = (await db.execute(
        select(Comment).where(Comment.post_id == p.id).order_by(Comment.created_at.asc())
    )).scalars().all()
    comment_list = []
    for c in comments:
        cu = await db.get(User, c.user_id)
        comment_list.append({
            "id": c.id,
            "username": cu.username if cu else "",
            "content": c.content,
            "created_at": c.created_at,
        })
    category_breakdown = p.category_breakdown
    budget_breakdown = p.budget_breakdown or {}
    if mask_private_modules:
        if p.data_visibility != "public":
            category_breakdown = {}
        if p.budget_visibility != "public":
            budget_breakdown = {}
    liked = False
    if viewer_user_id is not None:
        liked = (await db.execute(
            select(Like).where(Like.post_id == p.id, Like.user_id == viewer_user_id)
        )).scalar_one_or_none() is not None
    return {
        "post_id": p.id,
        "username": author.username if author else "",
        "nickname": author.nickname if author else "",
        "month": p.month,
        "total_expense": p.total_expense,
        "category_breakdown": category_breakdown,
        "budget_breakdown": budget_breakdown,
        "top_category": p.top_category,
        "saving_tip": p.saving_tip,
        "likes": p.likes,
        "liked": liked,
        "visibility": p.visibility,
        "data_visibility": p.data_visibility,
        "budget_visibility": p.budget_visibility,
        "comments": comment_list,
        "created_at": p.created_at,
        "updated_at": p.updated_at or p.created_at,
    }


@router.get("/stats/public", response_model=None)
async def public_stats(
    user: User | None = Depends(get_current_user_optional),
    db: AsyncSession = Depends(get_db),
):
    """获取所有用户公开统计数据（无需 token；带 token 时附带当前用户对每帖的点赞状态 liked）。

    双重过滤：
    - 帖子级：posts.visibility = 'public'（仅自己可见的帖子不出现）
    - 用户级：发帖用户全局开关 users.is_data_public = 1（关闭全局开关的用户所有帖子都不出现）
    """
    posts = (await db.execute(
        select(Post)
        .join(User, Post.user_id == User.id)
        .where(Post.visibility == "public", User.is_data_public == 1)
        .order_by(Post.created_at.desc())
    )).scalars().all()
    result = []
    for p in posts:
        result.append(await _post_to_dict(
            p, db,
            mask_private_modules=True,
            viewer_user_id=user.id if user else None,
        ))
    return ok(result)


@router.get("/posts/mine", response_model=None)
async def my_posts(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """获取当前用户发布的帖子（需 token；登录后客户端用于恢复本地数据）"""
    posts = (await db.execute(
        select(Post).where(Post.user_id == user.id).order_by(Post.created_at.desc())
    )).scalars().all()
    result = []
    for p in posts:
        result.append(await _post_to_dict(p, db, viewer_user_id=user.id))
    return ok(result)


@router.post("/posts", response_model=None)
async def create_post(
    body: PostCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """发布统计数据到社区（按 用户+月份 幂等）：

    - 首次创建：created_at 优先取客户端 timestamp（毫秒），否则用服务器当前时间；
    - 帖子已存在：仅更新统计内容、likes、comments，created_at 保持不变；
    - 历史重复帖子自动合并到最早一条并删除（保留首次创建时间）。
    - 两个独立模块：category_breakdown（消费数据）与 budget_breakdown（预算方案），
      均为空时返回 400「至少选择一个模块」。
    """
    category_breakdown = body.category_breakdown or {}
    budget_breakdown = body.budget_breakdown or {}
    if not category_breakdown and not budget_breakdown:
        raise HTTPException(status_code=400, detail="至少选择一个模块")
    # 可见度：仅接受 public / private，非法值回退为 public
    visibility = _norm_visibility(body.visibility)
    data_visibility = _norm_visibility(body.data_visibility)
    budget_visibility = _norm_visibility(body.budget_visibility)

    existing = (await db.execute(
        select(Post).where(Post.user_id == user.id, Post.month == body.month)
        .order_by(Post.created_at.asc())
    )).scalars().all()

    if existing:
        post = existing[0]  # 保留最早创建的帖子，created_at 不变
        # 合并并清理历史重复帖子（评论/点赞改挂到保留帖）
        for dup in existing[1:]:
            for c in (await db.execute(
                select(Comment).where(Comment.post_id == dup.id)
            )).scalars().all():
                c.post_id = post.id
            for l in (await db.execute(
                select(Like).where(Like.post_id == dup.id)
            )).scalars().all():
                l.post_id = post.id
            post.likes += dup.likes
            await db.delete(dup)

        # 只更新内容字段：created_at 保持不变，updated_at 刷新为当前时间
        post.total_expense = body.total_expense
        post.category_breakdown = category_breakdown
        post.budget_breakdown = budget_breakdown
        post.top_category = body.top_category
        post.saving_tip = body.saving_tip
        post.visibility = visibility
        post.data_visibility = data_visibility
        post.budget_visibility = budget_visibility
        post.updated_at = datetime.now()
        if body.likes is not None:
            post.likes = max(0, body.likes)
        if body.comments is not None:
            await _sync_comments(db, post, body.comments)
        await db.commit()
        await db.refresh(post)
        return ok({
            "post_id": post.id,
            "created_at": post.created_at,
            "updated_at": post.updated_at,
            "updated": True,
        })

    # 首次创建：客户端 timestamp 优先，否则服务器当前时间；updated_at 与 created_at 相同
    created_at = (
        datetime.fromtimestamp(body.timestamp / 1000)
        if body.timestamp
        else datetime.now()
    )
    post = Post(
        user_id=user.id,
        month=body.month,
        total_expense=body.total_expense,
        category_breakdown=category_breakdown,
        budget_breakdown=budget_breakdown,
        top_category=body.top_category,
        saving_tip=body.saving_tip,
        visibility=visibility,
        data_visibility=data_visibility,
        budget_visibility=budget_visibility,
        created_at=created_at,
        updated_at=created_at,
    )
    if body.likes is not None:
        post.likes = max(0, body.likes)
    db.add(post)
    await db.commit()
    await db.refresh(post)
    if body.comments:
        await _sync_comments(db, post, body.comments)
    return ok({
        "post_id": post.id,
        "created_at": post.created_at,
        "updated_at": post.updated_at,
        "updated": False,
    })


@router.put("/posts/{post_id}", response_model=None)
async def update_post(
    post_id: int,
    body: PostUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """更新帖子可见度（需 token，仅本人）：帖子级 visibility 与模块级 data_visibility /
    budget_visibility 均可选，不传则保持原值；返回脱敏后的完整帖子。
    """
    post = await db.get(Post, post_id)
    if post is None:
        raise HTTPException(status_code=404, detail="帖子不存在")
    if post.user_id != user.id:
        raise HTTPException(status_code=403, detail="只能修改自己的帖子")

    if body.visibility is not None:
        post.visibility = _norm_visibility(body.visibility)
    if body.data_visibility is not None:
        post.data_visibility = _norm_visibility(body.data_visibility)
    if body.budget_visibility is not None:
        post.budget_visibility = _norm_visibility(body.budget_visibility)

    post.updated_at = datetime.now()
    await db.commit()
    await db.refresh(post)
    return ok(await _post_to_dict(post, db))


async def _sync_comments(db: AsyncSession, post: Post, items: list[CommentItem]) -> None:
    """将客户端回传的评论写入评论表（按 用户+内容 去重，避免重复插入）"""
    existing = (await db.execute(
        select(Comment).where(Comment.post_id == post.id)
    )).scalars().all()
    existing_keys = {(c.user_id, c.content) for c in existing}
    for item in items:
        content = item.content.strip()
        if not content:
            continue
        cu = (await db.execute(
            select(User).where(User.username == item.username)
        )).scalar_one_or_none()
        if cu is None or (cu.id, content) in existing_keys:
            continue
        db.add(Comment(post_id=post.id, user_id=cu.id, content=content))
    await db.commit()


@router.post("/comments", response_model=None)
async def create_comment(
    body: CommentCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """发表评论"""
    content = body.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="评论内容不能为空")
    post = await db.get(Post, body.post_id)
    if post is None:
        raise HTTPException(status_code=404, detail="帖子不存在")

    comment = Comment(post_id=body.post_id, user_id=user.id, content=content)
    db.add(comment)
    await db.commit()
    await db.refresh(comment)
    return ok({
        "id": comment.id,
        "username": user.username,
        "content": comment.content,
        "created_at": comment.created_at,
    })


@router.get("/comments/{post_id}", response_model=None)
async def list_comments(post_id: int, db: AsyncSession = Depends(get_db)):
    """获取评论列表（无需 token）"""
    post = await db.get(Post, post_id)
    if post is None:
        raise HTTPException(status_code=404, detail="帖子不存在")

    comments = (await db.execute(
        select(Comment).where(Comment.post_id == post_id).order_by(Comment.created_at.asc())
    )).scalars().all()
    result = []
    for c in comments:
        cu = await db.get(User, c.user_id)
        result.append({
            "id": c.id,
            "username": cu.username if cu else "",
            "content": c.content,
            "created_at": c.created_at,
        })
    return ok(result)


@router.post("/like/{post_id}", response_model=None)
async def toggle_like(
    post_id: int,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """点赞 / 取消点赞（同一用户对同一帖子只能有一条点赞记录，再次调用即取消）"""
    post = await db.get(Post, post_id)
    if post is None:
        raise HTTPException(status_code=404, detail="帖子不存在")

    like = (await db.execute(
        select(Like).where(Like.post_id == post_id, Like.user_id == user.id)
    )).scalar_one_or_none()

    if like is None:
        db.add(Like(post_id=post_id, user_id=user.id))
        post.likes += 1
    else:
        await db.delete(like)
        post.likes = max(0, post.likes - 1)

    await db.commit()
    await db.refresh(post)
    # liked = toggle 后当前用户是否已赞（like 为 None 表示原无记录 → 本次新增点赞 → 已赞），
    # 作为客户端点赞状态的权威来源，客户端据此校准本地 likedIds，避免错位。
    return ok({"likes": post.likes, "liked": like is None})
