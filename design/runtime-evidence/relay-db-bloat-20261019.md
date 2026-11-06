# relay.db 膨胀到 21 GB —— 根因与修复

> 日期：2026-10-19
> 环境：macOS，桌面端 `~/Library/Application Support/捎话 Tidings/relay.db`

## 现象

用户报「磁盘空间不够」。实测根盘只剩 **157 MiB**（100%），其中这个库占 **21 GB**。
而库里真实数据只有：

```
MessageEntity        941 条    2.1 MB
AgentEventEntity    7413 条    3.5 MB
AgentSessionEntity   135 条
AgentWorkspaceEntity  15 条
```

`dbstat` 定位（21 GB 的去向）：

```
MessageFts_data     21,330.9 MB   ← 全文索引
MessageFts_docsize        47.8 MB
MessageFts_idx             7.4 MB
AgentEventEntity           3.5 MB
MessageEntity              2.1 MB
```

**索引里有 4,554,999 条"文档"，真实消息 941 条** —— 膨胀 4,840 倍。

## 根因（两个，都在 `RelayDatabase.sq`）

### ① `INSERT OR REPLACE` 的隐式删除不触发 DELETE 触发器

`insertMessage` 用的是 `INSERT OR REPLACE`。SQLite 在撞主键时走的是"隐式删除 + 插入"，
而**隐式删除不激活 `AFTER DELETE` 触发器**。于是同一 id 每被重写一次，旧 FTS 行就永久
留下一条孤儿。而消息落盘是**整段线程重写**（`save(threads)`），所以每条消息都会被反复复制。

### ② 改与正文无关的列也重建索引

`updateDelivery` 只动 `delivery`（发送状态 Sending→Sent 之类），正文没变，但
`AFTER UPDATE` 触发器**无条件** delete+insert。每改一次状态就多一条。

## 修复

```sql
-- 只有当正文真的变了才重建索引
CREATE TRIGGER message_after_update AFTER UPDATE OF text ON MessageEntity
WHEN old.text IS NOT new.text
BEGIN
    INSERT INTO MessageFts(MessageFts, rowid, text) VALUES ('delete', old.rowid, old.text);
    INSERT INTO MessageFts(rowid, text) VALUES (new.rowid, new.text);
END;
```

并且插入前**显式**补掉 REPLACE 隐式删除本该触发的那一次维护：

```sql
insertMessageDropStaleIndex:
INSERT INTO MessageFts(MessageFts, rowid, text)
SELECT 'delete', m.rowid, m.text FROM MessageEntity m WHERE m.id = ?;
```

用 FTS5 的 `'delete'` 命令而不是 `DELETE FROM MessageFts` 是必须的：`content='MessageEntity'`
的外部内容表只能靠这条命令维护索引，直接 DELETE 会让索引与内容表不一致。

调用侧在同一个事务里成对执行，并对**没有 fts5 的机器**（Android 框架 SQLite，
见 `FtsTolerantSchema`）用 `runCatching` 兜底 —— 那种机器本来就没有索引要维护，
而写消息不能被它带崩。

## 清理过程（可复现）

**`DROP TABLE MessageFts` 是错的方法**：它要逐行删除 455 万条索引段，实测 6 分钟仍卡在
I/O 上（CPU 只用了 24s，WAL 没有增长）。正确做法是**反向**：新建空库，只复制真实表。

```bash
# 1. 导出真实表的 DDL
for t in MessageEntity AgentEventEntity ...; do sqlite3 relay.db ".schema $t"; done > schema.sql
# 2. 建新库 + ATTACH 原库整表复制（0 秒完成）
sqlite3 new.db < schema.sql
sqlite3 new.db "ATTACH 'relay.db' AS old;
  INSERT INTO MessageEntity SELECT * FROM old.MessageEntity;
  ..."
# 3. 逐表校验 + 内容校验和，然后替换
```

校验（替换前）：

```
MessageEntity        new=941  old=941   OK
AgentEventEntity     new=7413 old=7413  OK
（9 张表全部 OK）
消息正文校验和：941 | 1497667 | 1686588080023079  ← 两边逐字相同
事件校验和：    7413 | 1691389 | 12272197524710202 ← 两边逐字相同
integrity_check: ok
```

结果：**21 GB → 7.1 MB**，磁盘 157 MiB → **25 GiB**。应用启动后 FTS 自动重建，
`messages=941 / fts_docs=941`（1:1）。

## 回归门

`composeApp/src/jvmTest/.../SqlDelightMessageStoreFtsTest.kt`
:: `rewritingMessagesDoesNotAccumulateOrphanIndexRows`
—— 同 id 重写 30 次 + 只改状态 10 次，要求 `MessageFts_docsize` 始终 == `MessageEntity` 行数。

**注入验证**：把触发器改回无条件、去掉显式清理 ⇒ 测试立刻变红；恢复 ⇒ 变绿。
