-- 工单主表：状态是这道流水线的唯一真相源，转移合法性由 TicketStatus 状态机把守，
-- 每次流转都带 WHERE status = ? 的 CAS 条件（见 TicketRepository#transition），
-- 并发下不会出现"两个执行体都把同一单标成已解决"。
CREATE TABLE IF NOT EXISTS ticket (
    id              VARCHAR(36)  PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    description     CLOB         NOT NULL,
    category        VARCHAR(40),
    priority        VARCHAR(10),
    status          VARCHAR(20)  NOT NULL,
    suggested_reply CLOB,
    confidence      DOUBLE,
    citations_json  CLOB,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      CLOB,
    next_retry_at   BIGINT       NOT NULL DEFAULT 0,
    conversation_id VARCHAR(64),
    resolved_by     VARCHAR(20),
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ticket_retry ON ticket (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_ticket_list ON ticket (updated_at DESC);

-- 处理记录：谁在什么时点留下什么判断（Agent / 人工都写这张表，审核轨迹可回放）
CREATE TABLE IF NOT EXISTS ticket_note (
    id         VARCHAR(36) PRIMARY KEY,
    ticket_id  VARCHAR(36) NOT NULL,
    author     VARCHAR(20) NOT NULL,
    body       CLOB        NOT NULL,
    created_at BIGINT      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ticket_note ON ticket_note (ticket_id, created_at);
