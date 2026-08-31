"""交易记录接口：查询 / 批量同步 / 删除（均需登录 token）"""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from auth import get_current_user
from database import get_db
from models import Transaction, User
from schemas import SyncRequest, TransactionOut, ok

router = APIRouter(prefix="/api", tags=["transactions"])


@router.get("/transactions", response_model=None)
async def list_transactions(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """获取当前用户所有记录（按时间倒序）"""
    rows = (await db.execute(
        select(Transaction)
        .where(Transaction.user_id == user.id)
        .order_by(Transaction.timestamp.desc())
    )).scalars().all()
    return ok([TransactionOut.model_validate(t) for t in rows])


@router.post("/transactions/sync", response_model=None)
async def sync_transactions(
    payload: SyncRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """
    批量同步本地记录（增量上传）：
    - localId 已存在则跳过（幂等，不会重复入库）
    - 失败的记录 id 收集到 failed_ids 返回，客户端可稍后重试
    """
    synced_count = 0
    failed_ids: list[str] = []
    now = datetime.now()

    for t in payload.transactions:
        try:
            if t.localId:
                # 幂等检查必须限定当前用户：local_id 全局唯一，若不按 user_id 过滤，
                # 一旦客户端带错 token 把数据写入别的账号，本账号的记录会被永久跳过
                exists = (await db.execute(
                    select(Transaction).where(
                        Transaction.local_id == t.localId,
                        Transaction.user_id == user.id,
                    )
                )).scalar_one_or_none()
                if exists:
                    continue  # 已同步过，跳过
            db.add(Transaction(
                user_id=user.id,
                local_id=t.localId or None,
                amount=t.amount,
                category=t.category,
                type=t.type,
                note=t.note,
                timestamp=t.timestamp,
                is_public=t.is_public,
                synced_at=now,
            ))
            synced_count += 1
        except Exception:
            failed_ids.append(t.localId)

    await db.commit()
    return ok({"synced_count": synced_count, "failed_ids": failed_ids})


@router.delete("/transactions/{transaction_id}", response_model=None)
async def delete_transaction(
    transaction_id: int,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """删除记录（校验归属：只能删除自己的）"""
    t = await db.get(Transaction, transaction_id)
    if t is None:
        raise HTTPException(status_code=404, detail="记录不存在")
    if t.user_id != user.id:
        raise HTTPException(status_code=403, detail="无权删除他人记录")
    await db.delete(t)
    await db.commit()
    return ok(msg="已删除")
