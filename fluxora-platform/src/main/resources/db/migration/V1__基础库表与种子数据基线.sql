-- ============================================================================
-- Fluxora V1：基础库表与种子数据基线。
-- 本迁移为原 V1~V18 的合并基线：在所有环境重置后，以单次迁移建立全部业务表、
-- 约束、索引、序列与 COMMENT 元数据，并写入角色 / 权限等种子数据。
-- 合并原因：网关计费由「余额预冻结」重构为「请求后直接结算」，相关历史迁移
-- （V5 额度账户、V15 结算与对账等）整体改写；由于已无环境应用过旧版本
-- （CI 使用全新 testcontainers 库，本地 dev 库已重置），故将全部迁移压扁为
-- 单一基线，避免维护冗余的中间态迁移与校验和漂移。
-- 说明：表与字段的中文业务语义通过下方 COMMENT ON TABLE / COMMENT ON COLUMN
-- 写入数据库 DDL 元数据；运行时初始管理员账号由应用启动时按配置初始化，不在本迁移内。
-- ============================================================================

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: api_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_key (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    key_prefix character varying(20) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    expire_at timestamp with time zone,
    last_used_at timestamp with time zone,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    lookup_hash character(64) NOT NULL,
    lookup_hash_version smallint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_api_key_lookup_hash_hex CHECK ((lookup_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT chk_api_key_lookup_hash_version CHECK ((lookup_hash_version = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE api_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.api_key IS 'API Key 元数据表：仅保存前缀与 HMAC 哈希，绝不保存完整明文；完整 Key 仅在创建响应中返回一次';


--
-- Name: COLUMN api_key.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.id IS '主键，自增序列';


--
-- Name: COLUMN api_key.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.tenant_id IS '所属租户：与 user_id 联合形成租户内归属关系；用于跨租户隔离与索引剪枝';


--
-- Name: COLUMN api_key.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.user_id IS '所属租户用户：仅 TENANT 作用域用户可拥有 API Key';


--
-- Name: COLUMN api_key.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.name IS 'Key 名称：用户可读，允许字母/数字/空格/中文/常见标点，长度 2-64';


--
-- Name: COLUMN api_key.key_prefix; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.key_prefix IS '公开前缀：格式 flx_XXXXXXXX，作为索引列供网关按前缀快速定位';


--
-- Name: COLUMN api_key.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.enabled IS '启用状态：false 时即使未过期也视为 DISABLED，不能被使用';


--
-- Name: COLUMN api_key.expire_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.expire_at IS '过期时间：超过后视为 EXPIRED；NULL 表示永不过期';


--
-- Name: COLUMN api_key.last_used_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.last_used_at IS '最后使用时间：本轮预留字段；未来网关回写';


--
-- Name: COLUMN api_key.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.deleted_at IS '软删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻（遵循 AGENT.md 软删除规范）';


--
-- Name: COLUMN api_key.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.created_at IS '创建时间';


--
-- Name: COLUMN api_key.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.updated_at IS '最后更新时间';


--
-- Name: COLUMN api_key.lookup_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.lookup_hash IS 'Gateway 查找摘要：HMAC-SHA-256(APIKEY_LOOKUP_SECRET, canonical API Key) 的 64 位小写十六进制值；绝不保存明文；version=0 仅表示不可重建的历史停用 Key';


--
-- Name: COLUMN api_key.lookup_hash_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.api_key.lookup_hash_version IS 'Lookup 摘要算法版本：1 为当前完整 API Key HMAC；0 为迁移前不可安全重建且已停用的历史记录';


--
-- Name: api_key_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_key_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_key_id_seq OWNED BY public.api_key.id;


--
-- Name: billing_settlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_settlement (
    id bigint NOT NULL,
    request_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    api_key_id bigint NOT NULL,
    currency_code character varying(3) NOT NULL,
    actual_amount numeric(24,8),
    outstanding_amount numeric(24,8) DEFAULT 0 NOT NULL,
    status character varying(32) NOT NULL,
    reason_code character varying(64),
    tenant_model_id bigint NOT NULL,
    tenant_model_code character varying(128) NOT NULL,
    inbound_protocol character varying(32) NOT NULL,
    endpoint character varying(128) NOT NULL,
    price_version integer NOT NULL,
    input_price_per_million numeric(24,8) NOT NULL,
    output_price_per_million numeric(24,8) NOT NULL,
    cache_write_price_per_million numeric(24,8),
    cache_read_price_per_million numeric(24,8),
    upstream_dispatch_state character varying(32) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    finalized_at timestamp with time zone,
    reconciled_at timestamp with time zone,
    reconciled_by bigint,
    reconciliation_note character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_settlement_amount_nonnegative CHECK ((((actual_amount IS NULL) OR (actual_amount >= (0)::numeric)) AND (outstanding_amount >= (0)::numeric))),
    CONSTRAINT chk_settlement_dispatch_state CHECK (((upstream_dispatch_state)::text = ANY ((ARRAY['NOT_DISPATCHED'::character varying, 'DISPATCHED'::character varying, 'RESPONSE_STARTED'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT chk_settlement_status CHECK (((status)::text = ANY ((ARRAY['SETTLED'::character varying, 'NO_CHARGE'::character varying, 'RECONCILIATION_PENDING'::character varying])::text[])))
);


--
-- Name: TABLE billing_settlement; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.billing_settlement IS '模型请求直接结算事实表：一条 request_id 只处理一次；保存价格、用量与对账状态';


--
-- Name: COLUMN billing_settlement.request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.request_id IS 'Gateway 生成的随机请求标识，也是终态事件、直接结算与人工对账的幂等主键';


--
-- Name: COLUMN billing_settlement.actual_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.actual_amount IS '可信 usage 计算出的最终实扣金额；未知或待对账时为空';


--
-- Name: COLUMN billing_settlement.outstanding_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.outstanding_amount IS '当前保留字段，用于未来人工差异说明；本轮直接结算不自动追补未知 usage';


--
-- Name: COLUMN billing_settlement.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.status IS '结算状态：SETTLED 已直接扣费，NO_CHARGE 明确不扣费，RECONCILIATION_PENDING 等待人工确认';


--
-- Name: COLUMN billing_settlement.reason_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.reason_code IS '安全状态原因，例如 USAGE_UNKNOWN、TERMINAL_EVENT_TIMEOUT 或 UPSTREAM_NOT_DISPATCHED；不得保存上游原始错误';


--
-- Name: COLUMN billing_settlement.upstream_dispatch_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.upstream_dispatch_state IS '安全派发状态：NOT_DISPATCHED、DISPATCHED、RESPONSE_STARTED 或 UNKNOWN；仅 NOT_DISPATCHED 可自动不扣费';


--
-- Name: COLUMN billing_settlement.reconciliation_note; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.billing_settlement.reconciliation_note IS '平台管理员人工确认的简短审计原因；不允许保存 API Key、上游地址、正文或完整错误';


--
-- Name: billing_settlement_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.billing_settlement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: billing_settlement_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.billing_settlement_id_seq OWNED BY public.billing_settlement.id;


--
-- Name: credit_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credit_transaction (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    direction character varying(16) NOT NULL,
    delta numeric(24,8) NOT NULL,
    balance_before numeric(24,8) NOT NULL,
    balance_after numeric(24,8) NOT NULL,
    reason character varying(256) NOT NULL,
    operator_id bigint NOT NULL,
    operator_name character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source character varying(32) DEFAULT 'MANUAL_ADJUSTMENT'::character varying NOT NULL,
    card_id bigint,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL,
    transaction_type character varying(32) DEFAULT 'MANUAL_ADJUSTMENT'::character varying NOT NULL,
    billing_settlement_id bigint,
    CONSTRAINT chk_credit_txn_delta_positive CHECK ((delta > (0)::numeric)),
    CONSTRAINT chk_credit_txn_direction CHECK (((direction)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[]))),
    CONSTRAINT chk_credit_txn_source CHECK (((source)::text = ANY ((ARRAY['MANUAL_ADJUSTMENT'::character varying, 'CARD_REDEEM'::character varying, 'BILLING'::character varying])::text[]))),
    CONSTRAINT chk_credit_txn_type CHECK (((transaction_type)::text = ANY ((ARRAY['MANUAL_ADJUSTMENT'::character varying, 'CARD_REDEEM'::character varying, 'MODEL_USAGE'::character varying])::text[])))
);


--
-- Name: TABLE credit_transaction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.credit_transaction IS '额度流水：每次余额调整生成一条不可修改的审计记录；后端不提供 UPDATE/DELETE 接口';


--
-- Name: COLUMN credit_transaction.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.id IS '主键，自增序列';


--
-- Name: COLUMN credit_transaction.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.tenant_id IS '所属租户：来自被调整用户的租户归属，跨租户隔离';


--
-- Name: COLUMN credit_transaction.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.user_id IS '被调整的目标用户';


--
-- Name: COLUMN credit_transaction.direction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.direction IS '操作类型：CREDIT 增加 / DEBIT 扣减；CHECK 限制只能取这两个值';


--
-- Name: COLUMN credit_transaction.delta; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.delta IS '余额变动额，CNY 固定 8 位小数原子精度；必须与账户余额在同一事务内写入';


--
-- Name: COLUMN credit_transaction.balance_before; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.balance_before IS '变更前余额：由原子 UPDATE…RETURNING 在同一 SQL 中算得，保证审计连贯';


--
-- Name: COLUMN credit_transaction.balance_after; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.balance_after IS '变更后余额：来自同一 RETURNING 语句';


--
-- Name: COLUMN credit_transaction.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.reason IS '操作原因：管理员调整时必填；记录用户输入的中文说明';


--
-- Name: COLUMN credit_transaction.operator_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.operator_id IS '操作人 ID：执行调整的用户（管理员或未来网关账户）';


--
-- Name: COLUMN credit_transaction.operator_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.operator_name IS '操作人名称（反规范化）：写入时快照，避免未来用户更名后审计错乱';


--
-- Name: COLUMN credit_transaction.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.created_at IS '流水写入时间（即调整发生时间）';


--
-- Name: COLUMN credit_transaction.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.source IS '流水来源：MANUAL_ADJUSTMENT 管理员手工调整 / CARD_REDEEM 卡密充值；CHECK 限制取值';


--
-- Name: COLUMN credit_transaction.card_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.card_id IS '关联卡密 ID：仅 source=CARD_REDEEM 有值；通过下方部分唯一索引防止同一卡密重复入账';


--
-- Name: COLUMN credit_transaction.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.currency_code IS '不可篡改余额流水的记账币种；历史流水兼容回填为 CNY';


--
-- Name: COLUMN credit_transaction.transaction_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.transaction_type IS '流水业务类型：MANUAL_ADJUSTMENT、CARD_REDEEM 或 MODEL_USAGE；模型请求按最终可信用量直接扣费';


--
-- Name: COLUMN credit_transaction.billing_settlement_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.credit_transaction.billing_settlement_id IS '关联的模型请求直接结算记录；仅 MODEL_USAGE 流水填写，普通管理或卡密流水为空';


--
-- Name: credit_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.credit_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: credit_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.credit_transaction_id_seq OWNED BY public.credit_transaction.id;


--
-- Name: model_route; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_route (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    tenant_model_id bigint NOT NULL,
    inbound_protocol character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_model_route_protocol CHECK (((inbound_protocol)::text = ANY ((ARRAY['OPENAI'::character varying, 'ANTHROPIC'::character varying])::text[])))
);


--
-- Name: TABLE model_route; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.model_route IS '租户模型入站协议路由定义；同模型同协议只允许一条未删除路由，本轮不执行真实协议转换';


--
-- Name: COLUMN model_route.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.model_route.tenant_id IS '路由所属租户；服务层校验与 tenant_model.tenant_id 一致';


--
-- Name: COLUMN model_route.inbound_protocol; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.model_route.inbound_protocol IS '入站协议：OPENAI / ANTHROPIC；RouteTarget 引用的候选通道协议必须与之兼容';


--
-- Name: COLUMN model_route.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.model_route.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';


--
-- Name: model_route_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.model_route_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: model_route_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.model_route_id_seq OWNED BY public.model_route.id;


--
-- Name: permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permission (
    id bigint NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.permission IS '权限表：记录系统中所有可分配的权限项，权限码为稳定字符串编码';


--
-- Name: COLUMN permission.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.id IS '主键，自增序列';


--
-- Name: COLUMN permission.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.code IS '权限编码：全局唯一，格式为 PERM_{code}，供 @PreAuthorize 使用';


--
-- Name: COLUMN permission.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.name IS '权限名称，展示用';


--
-- Name: COLUMN permission.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.description IS '权限描述，说明该权限允许执行的操作';


--
-- Name: COLUMN permission.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.created_at IS '创建时间';


--
-- Name: COLUMN permission.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.permission.updated_at IS '最后更新时间';


--
-- Name: permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: permission_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.permission_id_seq OWNED BY public.permission.id;


--
-- Name: provider; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    code character varying(64) NOT NULL,
    scope_type character varying(32) NOT NULL,
    tenant_id bigint,
    description character varying(500),
    enabled boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_provider_scope CHECK (((scope_type)::text = ANY ((ARRAY['PLATFORM_SHARED'::character varying, 'TENANT_PRIVATE'::character varying])::text[]))),
    CONSTRAINT chk_provider_scope_tenant CHECK (((((scope_type)::text = 'PLATFORM_SHARED'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT_PRIVATE'::text) AND (tenant_id IS NOT NULL))))
);


--
-- Name: TABLE provider; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider IS '上游厂商：平台共享或指定租户私有的服务来源，不代表具体接入地址或路由通道';


--
-- Name: COLUMN provider.scope_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider.scope_type IS '来源范围：PLATFORM_SHARED 供全部租户选用，TENANT_PRIVATE 只归属一个租户';


--
-- Name: COLUMN provider.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider.tenant_id IS '私有上游所属租户；共享上游必须为空，避免模糊作用域';


--
-- Name: COLUMN provider.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider.deleted_at IS '逻辑删除时间；非空资源不可见且不参与唯一性约束';


--
-- Name: provider_base_url; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_base_url (
    id bigint NOT NULL,
    provider_id bigint NOT NULL,
    protocol character varying(32) NOT NULL,
    original_base_url character varying(1024) NOT NULL,
    normalized_base_url character varying(1024) NOT NULL,
    display_name character varying(128),
    remark character varying(500),
    enabled boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_provider_base_url_protocol CHECK (((protocol)::text = ANY ((ARRAY['OPENAI'::character varying, 'ANTHROPIC'::character varying])::text[])))
);


--
-- Name: TABLE provider_base_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider_base_url IS '上游逻辑接入基础地址：绑定一个厂商与一种协议，后续网关据此拼接业务接口路径';


--
-- Name: COLUMN provider_base_url.protocol; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_base_url.protocol IS '上游协议：当前 OPENAI 或 ANTHROPIC；同一物理 URL 可按不同协议并存';


--
-- Name: COLUMN provider_base_url.original_base_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_base_url.original_base_url IS '用户填写的接入基础 URL，仅允许协议、域名和公共路径';


--
-- Name: COLUMN provider_base_url.normalized_base_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_base_url.normalized_base_url IS '规范化 URL：去末尾斜杠、无 query/fragment，用于同协议唯一判断';


--
-- Name: provider_base_url_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_base_url_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_base_url_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_base_url_id_seq OWNED BY public.provider_base_url.id;


--
-- Name: provider_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_channel (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    provider_base_url_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    weight integer DEFAULT 100 NOT NULL,
    connect_timeout_ms integer DEFAULT 5000 NOT NULL,
    read_timeout_ms integer DEFAULT 60000 NOT NULL,
    remark character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_provider_channel_connect_timeout CHECK (((connect_timeout_ms >= 100) AND (connect_timeout_ms <= 120000))),
    CONSTRAINT chk_provider_channel_priority CHECK (((priority >= 0) AND (priority <= 100000))),
    CONSTRAINT chk_provider_channel_read_timeout CHECK (((read_timeout_ms >= 100) AND (read_timeout_ms <= 600000))),
    CONSTRAINT chk_provider_channel_weight CHECK (((weight >= 1) AND (weight <= 100000)))
);


--
-- Name: TABLE provider_channel; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider_channel IS '租户实际可选用的上游通道：引用一个可见的接入基础地址并保存运行参数';


--
-- Name: COLUMN provider_channel.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel.tenant_id IS '通道归属租户；租户管理员只能管理当前租户通道';


--
-- Name: COLUMN provider_channel.provider_base_url_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel.provider_base_url_id IS '引用的逻辑接入地址；创建时服务层校验共享或本租户私有可见性';


--
-- Name: COLUMN provider_channel.priority; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel.priority IS '未来路由优先级；当前仅保存配置，不执行调度';


--
-- Name: COLUMN provider_channel.weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel.weight IS '未来同优先级分流权重；当前仅保存配置';


--
-- Name: provider_channel_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_channel_credential (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    provider_channel_id bigint NOT NULL,
    provider_credential_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    billing_account_group character varying(128),
    quota_scope character varying(128),
    traffic_weight integer DEFAULT 1 NOT NULL,
    max_concurrent_streams integer DEFAULT 2147483647 NOT NULL,
    CONSTRAINT chk_pcc_max_concurrent_streams CHECK (((max_concurrent_streams >= 1) AND (max_concurrent_streams <= 2147483647))),
    CONSTRAINT chk_pcc_traffic_weight CHECK (((traffic_weight >= 1) AND (traffic_weight <= 100000)))
);


--
-- Name: TABLE provider_channel_credential; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider_channel_credential IS '通道与上游凭证绑定表：同租户的一个凭证可绑定多个通道；Gateway 仅根据有效绑定判断凭证池可用性，不读取密文';


--
-- Name: COLUMN provider_channel_credential.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.tenant_id IS '绑定所属租户；服务层强制与通道和凭证的 tenant_id 一致，禁止跨租户共享凭证';


--
-- Name: COLUMN provider_channel_credential.provider_channel_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.provider_channel_id IS '可使用该凭证池的租户通道；通道软删除或停用后绑定不参与运行时快照';


--
-- Name: COLUMN provider_channel_credential.provider_credential_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.provider_credential_id IS '被绑定的加密凭证；本轮 Gateway 不解密、不选择具体凭证，仅检查是否存在有效绑定';


--
-- Name: COLUMN provider_channel_credential.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.enabled IS '绑定启用状态：false 时不影响凭证实体本身，但当前通道不能使用该绑定';


--
-- Name: COLUMN provider_channel_credential.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.deleted_at IS '逻辑删除时间戳：NULL 表示有效绑定；非 NULL 表示解绑时刻，用于审计、未来恢复与排查';


--
-- Name: COLUMN provider_channel_credential.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.created_at IS '绑定创建时间';


--
-- Name: COLUMN provider_channel_credential.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.updated_at IS '绑定最后修改时间；运行时快照据此识别凭证池配置变化';


--
-- Name: COLUMN provider_channel_credential.billing_account_group; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.billing_account_group IS '上游账务账户组；多把凭证共享同一上游余额时填写相同值，Gateway 按组排除与均衡，不含密钥或厂商账号明文';


--
-- Name: COLUMN provider_channel_credential.quota_scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.quota_scope IS '上游限流池；多把凭证共享同一 RPM/TPM 池时填写相同值，Gateway 按池排除与均衡，不含密钥或请求内容';


--
-- Name: COLUMN provider_channel_credential.traffic_weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.traffic_weight IS '绑定流量权重：同一 quotaScope 内 Credential 级加权最少活跃流使用，默认 1';


--
-- Name: COLUMN provider_channel_credential.max_concurrent_streams; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_credential.max_concurrent_streams IS '单绑定最大并发 Attempt 数；Gateway Redis 租约按该硬上限保护，默认近似无限制';


--
-- Name: provider_channel_credential_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_channel_credential_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_channel_credential_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_channel_credential_id_seq OWNED BY public.provider_channel_credential.id;


--
-- Name: provider_channel_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_channel_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_channel_id_seq OWNED BY public.provider_channel.id;


--
-- Name: provider_channel_model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_channel_model (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    provider_channel_id bigint NOT NULL,
    upstream_model_id character varying(256) NOT NULL,
    upstream_display_name character varying(256),
    source_type character varying(32) DEFAULT 'MANUAL'::character varying NOT NULL,
    supports_streaming boolean DEFAULT false NOT NULL,
    supports_tool_calling boolean DEFAULT false NOT NULL,
    supports_vision boolean DEFAULT false NOT NULL,
    supports_cache boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_synced_at timestamp with time zone,
    last_sync_summary character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_channel_model_source CHECK (((source_type)::text = ANY ((ARRAY['SYNCED'::character varying, 'MANUAL'::character varying])::text[])))
);


--
-- Name: TABLE provider_channel_model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider_channel_model IS '租户上游模型候选：必须属于具体租户在具体 provider_channel 下的可用上游模型，不再映射到任何全局模型';


--
-- Name: COLUMN provider_channel_model.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.tenant_id IS '候选所属租户；服务层强制与 provider_channel.tenant_id 一致，禁止跨租户引用';


--
-- Name: COLUMN provider_channel_model.provider_channel_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.provider_channel_id IS '所属通道；通道与候选必须同一租户，通道软删除后候选不应继续被映射';


--
-- Name: COLUMN provider_channel_model.upstream_model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.upstream_model_id IS '向上游传递的模型标识；路由目标只能通过候选映射间接引用，禁止前端自由输入';


--
-- Name: COLUMN provider_channel_model.source_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.source_type IS '候选来源：MANUAL 手工维护，SYNCED 通过同步发现（本轮不实现同步）';


--
-- Name: COLUMN provider_channel_model.supports_streaming; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.supports_streaming IS '上游是否支持流式输出；用于校验租户模型声明能力是否被有效候选支撑';


--
-- Name: COLUMN provider_channel_model.supports_tool_calling; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.supports_tool_calling IS '上游是否支持工具调用；同上';


--
-- Name: COLUMN provider_channel_model.supports_vision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.supports_vision IS '上游是否支持视觉输入；同上';


--
-- Name: COLUMN provider_channel_model.supports_cache; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.supports_cache IS '上游是否支持缓存命中；同上，且决定缓存读写价格是否必须配置';


--
-- Name: COLUMN provider_channel_model.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_channel_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';


--
-- Name: provider_channel_model_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_channel_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_channel_model_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_channel_model_id_seq OWNED BY public.provider_channel_model.id;


--
-- Name: provider_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_credential (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    credential_type character varying(32) DEFAULT 'API_KEY'::character varying NOT NULL,
    masked_value character varying(128) NOT NULL,
    credential_fingerprint character varying(64) NOT NULL,
    ciphertext text NOT NULL,
    initialization_vector character varying(64) NOT NULL,
    encryption_version character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    weight integer DEFAULT 100 NOT NULL,
    remark character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    auth_type character varying(32) DEFAULT 'BEARER'::character varying NOT NULL,
    credential_version bigint DEFAULT 1 NOT NULL,
    CONSTRAINT chk_provider_credential_auth_type CHECK (((auth_type)::text = ANY ((ARRAY['BEARER'::character varying, 'X_API_KEY'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT chk_provider_credential_priority CHECK (((priority >= 0) AND (priority <= 100000))),
    CONSTRAINT chk_provider_credential_type CHECK (((credential_type)::text = 'API_KEY'::text)),
    CONSTRAINT chk_provider_credential_version CHECK ((credential_version >= 1)),
    CONSTRAINT chk_provider_credential_weight CHECK (((weight >= 1) AND (weight <= 100000)))
);


--
-- Name: TABLE provider_credential; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.provider_credential IS '上游访问凭证：仅保存加密密文与去重指纹；通过 provider_channel_credential 绑定到一个或多个同租户通道，绝不向 Gateway 下发密文';


--
-- Name: COLUMN provider_credential.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.tenant_id IS '凭证所属租户；相同明文仅允许在同一租户保留一个未删除凭证实体，跨通道通过绑定复用';


--
-- Name: COLUMN provider_credential.masked_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.masked_value IS '仅供列表、详情和导入结果展示的脱敏标识，不含完整凭证';


--
-- Name: COLUMN provider_credential.credential_fingerprint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.credential_fingerprint IS 'HMAC-SHA-256 去重指纹；仅用于同租户凭证去重和迁移归并，绝不返回接口、日志或运行时快照';


--
-- Name: COLUMN provider_credential.ciphertext; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.ciphertext IS 'AES-GCM 加密密文；只允许控制面内部加解密流程访问，严禁进入 Redis、Gateway、DTO 或日志';


--
-- Name: COLUMN provider_credential.initialization_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.initialization_vector IS 'AES-GCM 初始化向量；仅控制面内部解密需要，严禁进入 Redis、Gateway、DTO 或日志';


--
-- Name: COLUMN provider_credential.encryption_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.encryption_version IS '加密版本标识：为未来密钥轮换预留，不暴露给普通调用方';


--
-- Name: COLUMN provider_credential.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.enabled IS '凭证实体启用状态：false 时所有绑定通道均不可使用该凭证；绑定自身仍可独立停用';


--
-- Name: COLUMN provider_credential.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.deleted_at IS '逻辑删除时间；已删除凭证不参与同租户重复判断，可重新导入';


--
-- Name: COLUMN provider_credential.auth_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.auth_type IS '上游认证注入方式：BEARER 写入 Authorization，X_API_KEY 写入 x-api-key，NONE 仅用于明确允许的无认证本地或测试上游';


--
-- Name: COLUMN provider_credential.credential_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.provider_credential.credential_version IS '凭证安全版本：密文、认证类型或启用状态变化时单调递增；路由引用与敏感运行时快照必须匹配该版本';


--
-- Name: provider_credential_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_credential_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_credential_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_credential_id_seq OWNED BY public.provider_credential.id;


--
-- Name: provider_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.provider_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: provider_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.provider_id_seq OWNED BY public.provider.id;


--
-- Name: recharge_card; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recharge_card (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    batch_id bigint NOT NULL,
    card_prefix character varying(20) NOT NULL,
    card_hash character(64) NOT NULL,
    denomination numeric(24,8) NOT NULL,
    status character varying(16) DEFAULT 'ENABLED'::character varying NOT NULL,
    expire_at timestamp with time zone,
    redeemed_user_id bigint,
    redeemed_at timestamp with time zone,
    disabled_reason character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL,
    CONSTRAINT chk_card_status CHECK (((status)::text = ANY ((ARRAY['ENABLED'::character varying, 'DISABLED'::character varying, 'REDEEMED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: TABLE recharge_card; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.recharge_card IS '单张卡密：HMAC-SHA256 哈希存储，绝不保存明文；明文仅在批次创建响应中返回一次';


--
-- Name: COLUMN recharge_card.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.id IS '主键，自增序列';


--
-- Name: COLUMN recharge_card.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.tenant_id IS '所属租户：与 batch.tenant_id 冗余但便于索引剪枝';


--
-- Name: COLUMN recharge_card.batch_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.batch_id IS '所属批次';


--
-- Name: COLUMN recharge_card.card_prefix; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.card_prefix IS '公开前缀：形如 FLX-XXXX，用于脱敏列表展示';


--
-- Name: COLUMN recharge_card.card_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.card_hash IS 'HMAC-SHA256(规范化明文, server_pepper) 的 hex；网关核销时用其校验输入';


--
-- Name: COLUMN recharge_card.denomination; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.denomination IS '面额：冗余自 batch，便于核销时无需 join';


--
-- Name: COLUMN recharge_card.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.status IS '状态：ENABLED 可核销 / DISABLED 已停用 / REDEEMED 已核销终态 / EXPIRED 已过期';


--
-- Name: COLUMN recharge_card.expire_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.expire_at IS '过期时间：冗余自 batch，便于核销时一次性 SQL 条件判断';


--
-- Name: COLUMN recharge_card.redeemed_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.redeemed_user_id IS '核销用户：仅 REDEEMED 状态有值';


--
-- Name: COLUMN recharge_card.redeemed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.redeemed_at IS '核销时间：与状态 REDEEMED 一一对应';


--
-- Name: COLUMN recharge_card.disabled_reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.disabled_reason IS '停用原因：可选，便于运营审计';


--
-- Name: COLUMN recharge_card.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.created_at IS '创建时间';


--
-- Name: COLUMN recharge_card.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.updated_at IS '最后更新时间';


--
-- Name: COLUMN recharge_card.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card.currency_code IS '单张卡密面额币种；必须与所属批次一致';


--
-- Name: recharge_card_batch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recharge_card_batch (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    batch_code character varying(32) NOT NULL,
    name character varying(128),
    denomination numeric(24,8) NOT NULL,
    total_count integer NOT NULL,
    status character varying(16) DEFAULT 'ENABLED'::character varying NOT NULL,
    expire_at timestamp with time zone,
    created_by_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL,
    CONSTRAINT chk_batch_denomination_positive CHECK ((denomination > (0)::numeric)),
    CONSTRAINT chk_batch_status CHECK (((status)::text = ANY ((ARRAY['ENABLED'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT chk_batch_total_positive CHECK ((total_count > 0))
);


--
-- Name: TABLE recharge_card_batch; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.recharge_card_batch IS '卡密批次：一次发卡操作的一个面额组；批次粒度便于运营统计与审计';


--
-- Name: COLUMN recharge_card_batch.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.id IS '主键，自增序列';


--
-- Name: COLUMN recharge_card_batch.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.tenant_id IS '所属租户：批次归属租户，跨租户隔离的根';


--
-- Name: COLUMN recharge_card_batch.batch_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.batch_code IS '业务编号：RCB-yyyymmdd-XXXX 形式，全局唯一，便于审计';


--
-- Name: COLUMN recharge_card_batch.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.name IS '批次备注：可选，便于运营理解用途';


--
-- Name: COLUMN recharge_card_batch.denomination; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.denomination IS '卡密面额，CNY 固定 8 位小数原子精度；历史数值安全保留';


--
-- Name: COLUMN recharge_card_batch.total_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.total_count IS '生成总张数：受 fluxora.security.card.batch-max-count 上限约束';


--
-- Name: COLUMN recharge_card_batch.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.status IS '批次状态：ENABLED 可用 / DISABLED 整批暂停核销';


--
-- Name: COLUMN recharge_card_batch.expire_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.expire_at IS '批次统一过期时间：超过后批内所有卡密视为 EXPIRED；NULL 表示永不过期';


--
-- Name: COLUMN recharge_card_batch.created_by_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.created_by_id IS '创建人：审计字段，记录发卡操作者';


--
-- Name: COLUMN recharge_card_batch.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.created_at IS '创建时间';


--
-- Name: COLUMN recharge_card_batch.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.updated_at IS '最后更新时间';


--
-- Name: COLUMN recharge_card_batch.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recharge_card_batch.currency_code IS '卡密批次面额币种；本期固定 CNY';


--
-- Name: recharge_card_batch_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.recharge_card_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: recharge_card_batch_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.recharge_card_batch_id_seq OWNED BY public.recharge_card_batch.id;


--
-- Name: recharge_card_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.recharge_card_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: recharge_card_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.recharge_card_id_seq OWNED BY public.recharge_card.id;


--
-- Name: relay_event_receipt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_event_receipt (
    event_id character varying(64) NOT NULL,
    request_id character varying(64) NOT NULL,
    event_type character varying(64) NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE relay_event_receipt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.relay_event_receipt IS 'Redis Stream 中继事件幂等收据：event_id 全局唯一；同一消息重复投递或重复消费时不得重复更新请求日志';


--
-- Name: COLUMN relay_event_receipt.event_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_event_receipt.event_id IS 'Gateway 生成的随机事件标识；不含 API Key、用户或模型文本';


--
-- Name: COLUMN relay_event_receipt.request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_event_receipt.request_id IS '关联的随机请求追踪标识，用于排查同一次请求的开始与终态事件';


--
-- Name: COLUMN relay_event_receipt.event_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_event_receipt.event_type IS '事件类型：RELAY_REQUEST_STARTED、RELAY_REQUEST_FINISHED、RELAY_REQUEST_FAILED 或 RELAY_REQUEST_CANCELLED';


--
-- Name: COLUMN relay_event_receipt.received_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_event_receipt.received_at IS 'Platform 成功写入 PostgreSQL 的接收时间';


--
-- Name: relay_request_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_request_log (
    id bigint NOT NULL,
    request_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    api_key_id bigint NOT NULL,
    inbound_protocol character varying(32) NOT NULL,
    outbound_protocol character varying(32) NOT NULL,
    endpoint character varying(128) NOT NULL,
    tenant_model_id bigint NOT NULL,
    tenant_model_code character varying(128) NOT NULL,
    route_target_id bigint NOT NULL,
    provider_channel_id bigint NOT NULL,
    provider_channel_model_id bigint NOT NULL,
    stream boolean DEFAULT false NOT NULL,
    request_status character varying(32) NOT NULL,
    error_category character varying(64),
    safe_http_status integer,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    duration_ms bigint,
    usage_status character varying(32) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    input_tokens bigint,
    output_tokens bigint,
    cache_write_tokens bigint,
    cache_read_tokens bigint,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL,
    price_version integer NOT NULL,
    input_price_per_million numeric(24,8) NOT NULL,
    output_price_per_million numeric(24,8) NOT NULL,
    cache_write_price_per_million numeric(24,8),
    cache_read_price_per_million numeric(24,8),
    theoretical_amount numeric(24,8),
    pricing_status character varying(32) NOT NULL,
    source_event_id character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    upstream_dispatch_state character varying(32) DEFAULT 'NOT_DISPATCHED'::character varying NOT NULL,
    billing_status character varying(32),
    actual_amount numeric(24,8),
    outstanding_amount numeric(24,8),
    CONSTRAINT chk_relay_dispatch_state CHECK (((upstream_dispatch_state)::text = ANY ((ARRAY['NOT_DISPATCHED'::character varying, 'DISPATCHED'::character varying, 'RESPONSE_STARTED'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT chk_relay_pricing_status CHECK (((pricing_status)::text = ANY ((ARRAY['CALCULATED'::character varying, 'PARTIAL'::character varying, 'UNAVAILABLE'::character varying, 'NOT_APPLICABLE'::character varying])::text[]))),
    CONSTRAINT chk_relay_request_status CHECK (((request_status)::text = ANY ((ARRAY['STARTED'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT chk_relay_token_nonnegative CHECK ((((input_tokens IS NULL) OR (input_tokens >= 0)) AND ((output_tokens IS NULL) OR (output_tokens >= 0)) AND ((cache_write_tokens IS NULL) OR (cache_write_tokens >= 0)) AND ((cache_read_tokens IS NULL) OR (cache_read_tokens >= 0)))),
    CONSTRAINT chk_relay_usage_status CHECK (((usage_status)::text = ANY ((ARRAY['REPORTED'::character varying, 'PARTIAL'::character varying, 'UNKNOWN'::character varying, 'NOT_APPLICABLE'::character varying])::text[])))
);


--
-- Name: TABLE relay_request_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.relay_request_log IS '中继请求安全观测日志：由 Platform 消费 Redis Stream 幂等写入，保存状态、用量与价格快照，不保存正文、密钥、凭证或上游地址';


--
-- Name: COLUMN relay_request_log.request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.request_id IS '随机全局请求追踪标识；跨租户查询仍必须经过 tenant_id 与用户权限校验';


--
-- Name: COLUMN relay_request_log.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.tenant_id IS '请求所属租户；所有列表、详情与趋势查询首先按此字段收缩范围';


--
-- Name: COLUMN relay_request_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.user_id IS '发起请求的用户；普通成员只能查看本人的记录';


--
-- Name: COLUMN relay_request_log.api_key_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.api_key_id IS '发起请求的 API Key 内部主键；永不存储 API Key 明文或前缀';


--
-- Name: COLUMN relay_request_log.tenant_model_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.tenant_model_code IS '租户对外模型编码；不存储上游模型标识';


--
-- Name: COLUMN relay_request_log.route_target_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.route_target_id IS '内部路由引用，仅供 Platform 消费与审计关联，公开接口不返回其详细配置';


--
-- Name: COLUMN relay_request_log.stream; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.stream IS '是否为 SSE 流式中继；不保存任意 SSE 文本分块';


--
-- Name: COLUMN relay_request_log.usage_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.usage_status IS '用量状态：REPORTED 完整、PARTIAL 部分、UNKNOWN 未上报、NOT_APPLICABLE 未调用上游';


--
-- Name: COLUMN relay_request_log.input_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.input_tokens IS '普通输入 Token；缓存读取 Token 已从本字段拆分，NULL 表示未知而非零';


--
-- Name: COLUMN relay_request_log.cache_write_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.cache_write_tokens IS '缓存写入 Token；NULL 表示上游未明确报告或当前不适用';


--
-- Name: COLUMN relay_request_log.cache_read_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.cache_read_tokens IS '缓存读取 Token；不得同时计入普通输入 Token';


--
-- Name: COLUMN relay_request_log.input_price_per_million; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.input_price_per_million IS '请求开始时固定的输入单价快照，每百万 Token、CNY 八位小数';


--
-- Name: COLUMN relay_request_log.theoretical_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.theoretical_amount IS '按价格快照在 Platform 高精度重算的理论金额；不代表扣费、余额、账单或结算';


--
-- Name: COLUMN relay_request_log.pricing_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.pricing_status IS '理论金额状态：只有 CALCULATED 行可进入金额汇总，未知或部分用量绝不按零计费';


--
-- Name: COLUMN relay_request_log.source_event_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.source_event_id IS '最后写入本行的 Redis Stream 事件标识；重复 event_id 由 relay_event_receipt 拦截';


--
-- Name: COLUMN relay_request_log.upstream_dispatch_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.upstream_dispatch_state IS '终态判断使用的安全上游派发状态；不记录上游 URL、正文或原始错误';


--
-- Name: COLUMN relay_request_log.billing_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.billing_status IS '关联直接结算的当前安全状态，供请求详情展示；不替代 billing_settlement 审计事实';


--
-- Name: COLUMN relay_request_log.actual_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.actual_amount IS '完整 usage 已知时的最终实际扣费金额；未知或待对账时为空';


--
-- Name: COLUMN relay_request_log.outstanding_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_request_log.outstanding_amount IS '待对账差异说明金额；本轮不代表自动追扣或用户可见欠款';


--
-- Name: relay_request_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.relay_request_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: relay_request_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.relay_request_log_id_seq OWNED BY public.relay_request_log.id;


--
-- Name: role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(512),
    scope_type character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.role IS '角色表：记录平台与租户级别的角色定义，通过作用域隔离保证安全边界';


--
-- Name: COLUMN role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.id IS '主键，自增序列';


--
-- Name: COLUMN role.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.code IS '角色编码：在同一作用域内唯一，用于程序识别';


--
-- Name: COLUMN role.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.name IS '角色名称，展示用';


--
-- Name: COLUMN role.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.description IS '角色描述，说明角色职责与权限范围';


--
-- Name: COLUMN role.scope_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.scope_type IS '角色作用域：PLATFORM 平台角色 / TENANT 租户角色，不可跨域分配';


--
-- Name: COLUMN role.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.created_at IS '创建时间';


--
-- Name: COLUMN role.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role.updated_at IS '最后更新时间';


--
-- Name: role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.role_id_seq OWNED BY public.role.id;


--
-- Name: role_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permission (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE role_permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.role_permission IS '角色权限关联表：记录角色与权限的多对多映射关系，角色通过此关联获得对应权限';


--
-- Name: COLUMN role_permission.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role_permission.id IS '主键，自增序列';


--
-- Name: COLUMN role_permission.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role_permission.role_id IS '角色 ID，引用 role 表';


--
-- Name: COLUMN role_permission.permission_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role_permission.permission_id IS '权限 ID，引用 permission 表';


--
-- Name: COLUMN role_permission.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.role_permission.created_at IS '分配时间';


--
-- Name: role_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.role_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: role_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.role_permission_id_seq OWNED BY public.role_permission.id;


--
-- Name: route_target; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.route_target (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    model_route_id bigint NOT NULL,
    tenant_model_candidate_mapping_id bigint NOT NULL,
    provider_channel_id bigint NOT NULL,
    upstream_model_id_snapshot character varying(256) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    weight integer DEFAULT 100 NOT NULL,
    remark character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_route_target_priority CHECK (((priority >= 0) AND (priority <= 100000))),
    CONSTRAINT chk_route_target_weight CHECK (((weight >= 1) AND (weight <= 100000)))
);


--
-- Name: TABLE route_target; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.route_target IS '路由目标：以 tenant_model_candidate_mapping 作为事实来源，承载优先级与权重；provider_channel_id 与 upstream_model_id_snapshot 仅为审计冗余';


--
-- Name: COLUMN route_target.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.tenant_id IS '路由目标所属租户；服务层校验四方一致（route / mapping / 候选 / 通道）';


--
-- Name: COLUMN route_target.model_route_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.model_route_id IS '所属路由；同一路由下不得重复引用同一映射';


--
-- Name: COLUMN route_target.tenant_model_candidate_mapping_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.tenant_model_candidate_mapping_id IS '事实来源：合法性校验、能力支撑判定、租户隔离全部以此为准';


--
-- Name: COLUMN route_target.provider_channel_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.provider_channel_id IS '审计冗余：候选映射对应通道的 id，仅用于查询展示与未来快照；写入与合法性校验仍以映射为准';


--
-- Name: COLUMN route_target.upstream_model_id_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.upstream_model_id_snapshot IS '审计冗余：创建时复制的上游模型标识，便于未来运行时快照与排查';


--
-- Name: COLUMN route_target.priority; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.priority IS '同路由内调度优先级；本轮仅保存配置，不参与真实调度';


--
-- Name: COLUMN route_target.weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.weight IS '同优先级分流权重；本轮仅保存配置，不参与真实调度';


--
-- Name: COLUMN route_target.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.route_target.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';


--
-- Name: route_target_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.route_target_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: route_target_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.route_target_id_seq OWNED BY public.route_target.id;


--
-- Name: runtime_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtime_outbox (
    id bigint NOT NULL,
    tenant_id bigint,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id bigint,
    mutation_type character varying(64) NOT NULL,
    impact_hint character varying(128),
    payload_version smallint DEFAULT 1 NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    locked_by character varying(128),
    locked_at timestamp with time zone,
    last_error_summary character varying(512),
    projected_at timestamp with time zone,
    notified_at timestamp with time zone,
    processed_at timestamp with time zone,
    CONSTRAINT chk_runtime_outbox_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_runtime_outbox_payload_version CHECK ((payload_version = 1)),
    CONSTRAINT chk_runtime_outbox_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'RETRY'::character varying, 'COMPLETED'::character varying])::text[])))
);


--
-- Name: TABLE runtime_outbox; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.runtime_outbox IS '运行时配置投影 Outbox：与控制面业务写入同一 PostgreSQL 事务提交；Projector 以幂等、可重试方式生成 Redis 运行时快照';


--
-- Name: COLUMN runtime_outbox.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.tenant_id IS '受影响租户；平台共享资源可为空，由 RuntimeImpactResolver 根据关联关系计算实际 Scope';


--
-- Name: COLUMN runtime_outbox.aggregate_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.aggregate_type IS '发生变更的领域实体类型；业务服务只记录来源实体，禁止自行决定 Redis Key 或 Gateway 缓存';


--
-- Name: COLUMN runtime_outbox.aggregate_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.aggregate_id IS '发生变更的实体主键；为空仅用于系统级全量重建或时间状态扫描任务';


--
-- Name: COLUMN runtime_outbox.mutation_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.mutation_type IS '标准化变更类型；ImpactResolver 根据实体与操作计算最小受影响运行时 Scope';


--
-- Name: COLUMN runtime_outbox.impact_hint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.impact_hint IS '可选影响提示；仅用于缩小数据库关联查询范围，不承载完整配置或敏感信息';


--
-- Name: COLUMN runtime_outbox.payload_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.payload_version IS 'Outbox 负载契约版本；本轮固定 1，升级时必须保持旧消费者可识别';


--
-- Name: COLUMN runtime_outbox.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.occurred_at IS '业务变更发生时间；用于排序、延迟观测和安全审计';


--
-- Name: COLUMN runtime_outbox.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.status IS '投影状态：PENDING 待处理、PROCESSING 已抢占、RETRY 等待退避、COMPLETED 快照与通知均成功';


--
-- Name: COLUMN runtime_outbox.attempt_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.attempt_count IS '累计投影尝试次数；用于指数退避与失败可观测性';


--
-- Name: COLUMN runtime_outbox.next_retry_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.next_retry_at IS '下一次可领取时间；索引支持 Projector 高效扫描';


--
-- Name: COLUMN runtime_outbox.locked_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.locked_by IS '抢占本记录的 Projector 实例标识；仅运维与故障排查使用';


--
-- Name: COLUMN runtime_outbox.locked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.locked_at IS '本次抢占时间；超时记录可由恢复任务安全重新领取';


--
-- Name: COLUMN runtime_outbox.last_error_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.last_error_summary IS '安全截断的失败摘要；不得包含 API Key、凭证明文/密文、SQL、堆栈或配置内容';


--
-- Name: COLUMN runtime_outbox.projected_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.projected_at IS '不可变快照与 Manifest 成功切换时间';


--
-- Name: COLUMN runtime_outbox.notified_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.notified_at IS 'Redis Pub/Sub 失效通知成功发布的时间';


--
-- Name: COLUMN runtime_outbox.processed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_outbox.processed_at IS '完整投影处理成功时间';


--
-- Name: runtime_outbox_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.runtime_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: runtime_outbox_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.runtime_outbox_id_seq OWNED BY public.runtime_outbox.id;


--
-- Name: runtime_projection_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtime_projection_state (
    state_key character varying(128) NOT NULL,
    state_value character varying(512),
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE runtime_projection_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.runtime_projection_state IS '运行时投影协调状态：保存时间边界扫描游标和重建标记，不保存 API Key、凭证、上游配置或用户隐私';


--
-- Name: COLUMN runtime_projection_state.state_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_projection_state.state_key IS '状态名称，例如 API_KEY_TIME_SCAN_CURSOR；由 Platform 内部固定枚举管理';


--
-- Name: COLUMN runtime_projection_state.state_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_projection_state.state_value IS '安全的协调值，例如 ISO-8601 扫描游标；不得写入 Redis Key、明文密钥或错误堆栈';


--
-- Name: COLUMN runtime_projection_state.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_projection_state.updated_at IS '协调状态最近更新时间';


--
-- Name: runtime_snapshot_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtime_snapshot_version (
    scope_type character varying(64) NOT NULL,
    scope_key character varying(512) NOT NULL,
    current_version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_runtime_snapshot_version_positive CHECK ((current_version >= 0))
);


--
-- Name: TABLE runtime_snapshot_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.runtime_snapshot_version IS '运行时快照版本分配表：按最小 Scope 持久化单调递增版本，防止重复、乱序或旧 Projector 覆盖高版本 Manifest';


--
-- Name: COLUMN runtime_snapshot_version.scope_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_snapshot_version.scope_type IS 'Scope 类型：AUTH_API_KEY、AUTH_USER、AUTH_TENANT、TENANT_MODEL_ROUTE 或 UPSTREAM_CREDENTIAL；后者为 Gateway 专用敏感运行时密文快照';


--
-- Name: COLUMN runtime_snapshot_version.scope_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_snapshot_version.scope_key IS 'Scope 的安全逻辑键；API Key Scope 仅使用 HMAC 摘要，模型编码使用稳定编码，不写 Redis 原始 Key';


--
-- Name: COLUMN runtime_snapshot_version.current_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_snapshot_version.current_version IS '当前已分配的严格递增运行时版本号；必须由单条 UPSERT 原子加一';


--
-- Name: COLUMN runtime_snapshot_version.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_snapshot_version.updated_at IS '最近一次版本分配时间；用于运行时滞后观测与故障排查';


--
-- Name: tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant (
    id bigint NOT NULL,
    tenant_code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    type character varying(32) DEFAULT 'STANDARD'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    expire_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(512),
    deleted_at timestamp with time zone,
    settlement_currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL
);


--
-- Name: TABLE tenant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant IS '租户表：记录平台中所有租户（自营与标准），租户码全局唯一，支持启用、过期与逻辑删除';


--
-- Name: COLUMN tenant.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.id IS '主键，自增序列';


--
-- Name: COLUMN tenant.tenant_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.tenant_code IS '租户码，全局唯一，用于业务标识与路由，创建后不可修改';


--
-- Name: COLUMN tenant.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.name IS '租户名称，对外展示用';


--
-- Name: COLUMN tenant.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.type IS '租户类型：SELF_OPERATED 自营租户（仅初始化创建）或 STANDARD 标准租户（API 创建）';


--
-- Name: COLUMN tenant.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.enabled IS '启用状态：false 时租户下所有用户无法登录和访问';


--
-- Name: COLUMN tenant.expire_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.expire_at IS '过期时间：超过后即使 enabled=true 也视为已过期，NULL 表示永不过期';


--
-- Name: COLUMN tenant.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.created_at IS '创建时间';


--
-- Name: COLUMN tenant.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.updated_at IS '最后更新时间';


--
-- Name: COLUMN tenant.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.description IS '租户描述：补充说明信息，如业务用途、归属部门等，可选';


--
-- Name: COLUMN tenant.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.deleted_at IS '逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计与未来窗口期恢复';


--
-- Name: COLUMN tenant.settlement_currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant.settlement_currency_code IS '租户当前结算币种；本期固定 CNY，为未来多币种结算预留稳定归属';


--
-- Name: tenant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_id_seq OWNED BY public.tenant.id;


--
-- Name: tenant_model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_model (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    model_code character varying(128) NOT NULL,
    display_name character varying(256) NOT NULL,
    description character varying(1000),
    supports_streaming boolean DEFAULT false NOT NULL,
    supports_tool_calling boolean DEFAULT false NOT NULL,
    supports_vision boolean DEFAULT false NOT NULL,
    supports_cache boolean DEFAULT false NOT NULL,
    publish_status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    default_output_tokens bigint DEFAULT 2048 NOT NULL,
    CONSTRAINT chk_tenant_model_default_output CHECK ((default_output_tokens > 0)),
    CONSTRAINT chk_tenant_model_status CHECK (((publish_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ENABLED'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: TABLE tenant_model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant_model IS '租户对外模型：唯一对 C 端用户售卖、定价、路由与发布的模型实体；不依赖任何全局模型目录';


--
-- Name: COLUMN tenant_model.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.tenant_id IS '模型所属租户；不同租户允许使用相同 model_code，但能力、价格、候选与路由完全独立';


--
-- Name: COLUMN tenant_model.model_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.model_code IS '租户内唯一的对外模型编码；删除后允许同租户重新使用（依赖部分唯一索引）';


--
-- Name: COLUMN tenant_model.supports_streaming; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.supports_streaming IS '租户声明对外支持流式输出，启用前必须有至少一个候选映射支撑';


--
-- Name: COLUMN tenant_model.supports_tool_calling; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.supports_tool_calling IS '租户声明对外支持工具调用，启用前必须有至少一个候选映射支撑';


--
-- Name: COLUMN tenant_model.supports_vision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.supports_vision IS '租户声明对外支持视觉输入，启用前必须有至少一个候选映射支撑';


--
-- Name: COLUMN tenant_model.supports_cache; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.supports_cache IS '租户声明对外支持缓存命中；启用前必须有候选支撑且四项价格中的缓存读写价格已配置';


--
-- Name: COLUMN tenant_model.publish_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.publish_status IS '发布状态：DRAFT 未完成配置；ENABLED 对 C 端可见；DISABLED 暂停展示';


--
-- Name: COLUMN tenant_model.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.enabled IS '冗余启用标记：与 publish_status=ENABLED 同步，用于公开目录索引快速过滤';


--
-- Name: COLUMN tenant_model.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';


--
-- Name: COLUMN tenant_model.default_output_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model.default_output_tokens IS '客户端未声明输出上限时 Gateway 写入并转发的确定性默认上限，必须不大于 max_output_tokens';


--
-- Name: tenant_model_candidate_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_model_candidate_mapping (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    tenant_model_id bigint NOT NULL,
    provider_channel_model_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark character varying(500),
    deleted_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tenant_model_candidate_mapping; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant_model_candidate_mapping IS '租户模型与上游候选的允许关系：三方 tenant_id 必须一致，禁止跨租户引用；不保存优先级、权重或协议（属于 RouteTarget）';


--
-- Name: COLUMN tenant_model_candidate_mapping.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_candidate_mapping.tenant_id IS '映射所属租户；服务层校验 tenant_model 与 provider_channel_model 均为同一租户';


--
-- Name: COLUMN tenant_model_candidate_mapping.tenant_model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_candidate_mapping.tenant_model_id IS '所映射的对外模型';


--
-- Name: COLUMN tenant_model_candidate_mapping.provider_channel_model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_candidate_mapping.provider_channel_model_id IS '所映射的上游候选；候选所属通道必须有效，通道与凭证停用时不得作为模型启用支撑';


--
-- Name: COLUMN tenant_model_candidate_mapping.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_candidate_mapping.enabled IS '映射启用标记：停用后不再作为 RouteTarget 启用候选源';


--
-- Name: COLUMN tenant_model_candidate_mapping.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_candidate_mapping.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻；被 ENABLED RouteTarget 引用的映射不得直接删除';


--
-- Name: tenant_model_candidate_mapping_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_model_candidate_mapping_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_model_candidate_mapping_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_model_candidate_mapping_id_seq OWNED BY public.tenant_model_candidate_mapping.id;


--
-- Name: tenant_model_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_model_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_model_id_seq OWNED BY public.tenant_model.id;


--
-- Name: tenant_model_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_model_price (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    tenant_model_id bigint NOT NULL,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL,
    input_price_per_million numeric(24,8) NOT NULL,
    output_price_per_million numeric(24,8) NOT NULL,
    cache_write_price_per_million numeric(24,8),
    cache_read_price_per_million numeric(24,8),
    version integer NOT NULL,
    effective_at timestamp with time zone DEFAULT now() NOT NULL,
    expired_at timestamp with time zone,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_price_currency CHECK (((currency_code)::text = 'CNY'::text)),
    CONSTRAINT chk_tenant_price_nonnegative CHECK (((input_price_per_million >= (0)::numeric) AND (output_price_per_million >= (0)::numeric) AND ((cache_write_price_per_million IS NULL) OR (cache_write_price_per_million >= (0)::numeric)) AND ((cache_read_price_per_million IS NULL) OR (cache_read_price_per_million >= (0)::numeric))))
);


--
-- Name: TABLE tenant_model_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant_model_price IS '租户模型对外售价历史：金额为每 100 万 Token 的 CNY 8 位小数原子精度值，新增版本而非覆盖';


--
-- Name: COLUMN tenant_model_price.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.tenant_id IS '价格所属租户；与 tenant_model.tenant_id 强一致，服务层兜底校验';


--
-- Name: COLUMN tenant_model_price.tenant_model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.tenant_model_id IS '价格归属的租户模型；不存在「跨租户复用价格」或「继承平台默认价」';


--
-- Name: COLUMN tenant_model_price.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.currency_code IS '价格币种；当前固定 CNY，字段保留用于未来多币种价格历史';


--
-- Name: COLUMN tenant_model_price.input_price_per_million; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.input_price_per_million IS '输入单价：每 100 万 Token，CNY 8 位小数；不支持 float/double';


--
-- Name: COLUMN tenant_model_price.output_price_per_million; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.output_price_per_million IS '输出单价：每 100 万 Token，CNY 8 位小数；不支持 float/double';


--
-- Name: COLUMN tenant_model_price.cache_write_price_per_million; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.cache_write_price_per_million IS '缓存写入单价：每 100 万 Token；不支持缓存时为 NULL';


--
-- Name: COLUMN tenant_model_price.cache_read_price_per_million; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.cache_read_price_per_million IS '缓存读取单价：每 100 万 Token；不支持缓存时为 NULL';


--
-- Name: COLUMN tenant_model_price.version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.version IS '同一租户模型内的价格版本号；服务层在事务内单调递增';


--
-- Name: COLUMN tenant_model_price.expired_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tenant_model_price.expired_at IS '价格失效时刻；NULL 表示当前有效版本，部分唯一索引兜底每模型仅一个有效价格';


--
-- Name: tenant_model_price_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_model_price_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_model_price_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_model_price_id_seq OWNED BY public.tenant_model_price.id;


--
-- Name: upstream_runtime_failure_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.upstream_runtime_failure_event (
    id bigint NOT NULL,
    event_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    request_id character varying(64) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    attempt_no integer NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    credential_id bigint,
    provider_channel_credential_id bigint,
    provider_channel_id bigint,
    provider_channel_model_id bigint,
    route_target_id bigint,
    billing_account_group character varying(128),
    quota_scope character varying(128),
    failure_kind character varying(64) NOT NULL,
    failure_scope character varying(64) NOT NULL,
    http_status integer,
    retry_after_ms bigint,
    execution_certainty character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_upstream_runtime_failure_attempt_no CHECK ((attempt_no >= 1)),
    CONSTRAINT chk_upstream_runtime_failure_retry_after CHECK (((retry_after_ms IS NULL) OR (retry_after_ms >= 0)))
);


--
-- Name: TABLE upstream_runtime_failure_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.upstream_runtime_failure_event IS 'Gateway 上报的上游运行时故障事件收据：仅包含脱敏资源 ID、失败分类和冷却信息，不保存 API Key、上游 Key、BaseUrl、正文或异常栈';


--
-- Name: COLUMN upstream_runtime_failure_event.event_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.event_id IS 'Gateway 生成的幂等事件 ID；重复消费时保证只处理一次';


--
-- Name: COLUMN upstream_runtime_failure_event.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.tenant_id IS '故障所属租户，用于运行时状态和快照影响范围收敛';


--
-- Name: COLUMN upstream_runtime_failure_event.request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.request_id IS '客户端请求 ID；多个内部 Attempt 共享同一 requestId';


--
-- Name: COLUMN upstream_runtime_failure_event.attempt_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.attempt_id IS '内部上游尝试 ID；每次调度租约对应一个独立 Attempt';


--
-- Name: COLUMN upstream_runtime_failure_event.attempt_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.attempt_no IS '当前请求内第几次 Attempt，从 1 开始';


--
-- Name: COLUMN upstream_runtime_failure_event.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.occurred_at IS 'Gateway 识别故障的时间';


--
-- Name: COLUMN upstream_runtime_failure_event.credential_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.credential_id IS '被影响的 provider_credential.id；仅为内部数字 ID，不含凭证内容';


--
-- Name: COLUMN upstream_runtime_failure_event.provider_channel_credential_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.provider_channel_credential_id IS '被影响的通道凭证绑定 ID';


--
-- Name: COLUMN upstream_runtime_failure_event.provider_channel_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.provider_channel_id IS '被影响的上游通道 ID';


--
-- Name: COLUMN upstream_runtime_failure_event.provider_channel_model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.provider_channel_model_id IS '被影响的通道模型 ID';


--
-- Name: COLUMN upstream_runtime_failure_event.route_target_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.route_target_id IS '被影响的路由目标 ID';


--
-- Name: COLUMN upstream_runtime_failure_event.billing_account_group; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.billing_account_group IS '被影响的上游账务账户组；来自安全配置，不含账号明文';


--
-- Name: COLUMN upstream_runtime_failure_event.quota_scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.quota_scope IS '被影响的上游限流池；来自安全配置，不含密钥或正文';


--
-- Name: COLUMN upstream_runtime_failure_event.failure_kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.failure_kind IS 'Gateway 统一失败分类，例如 AUTH_INVALID、RATE_LIMITED';


--
-- Name: COLUMN upstream_runtime_failure_event.failure_scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.failure_scope IS '失败影响范围，例如 CREDENTIAL、QUOTA_SCOPE';


--
-- Name: COLUMN upstream_runtime_failure_event.http_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.http_status IS '上游 HTTP 状态码；仅用于分类审计，不对用户透出';


--
-- Name: COLUMN upstream_runtime_failure_event.retry_after_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.retry_after_ms IS '结构化 Retry-After 转换后的毫秒冷却时间；为空表示无明确建议';


--
-- Name: COLUMN upstream_runtime_failure_event.execution_certainty; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.execution_certainty IS '执行确定性：NOT_EXECUTED、PRE_EXECUTION_REJECTED 或 POSSIBLY_EXECUTED';


--
-- Name: COLUMN upstream_runtime_failure_event.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_failure_event.created_at IS 'Platform 消费并持久化该事件的时间';


--
-- Name: upstream_runtime_failure_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.upstream_runtime_failure_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: upstream_runtime_failure_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.upstream_runtime_failure_event_id_seq OWNED BY public.upstream_runtime_failure_event.id;


--
-- Name: upstream_runtime_resource_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.upstream_runtime_resource_state (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    scope_type character varying(64) NOT NULL,
    scope_key character varying(192) NOT NULL,
    runtime_state character varying(64) NOT NULL,
    last_failure_kind character varying(64),
    last_failed_at timestamp with time zone,
    cooldown_until timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_upstream_runtime_scope_type CHECK (((scope_type)::text = ANY ((ARRAY['CREDENTIAL'::character varying, 'PROVIDER_CHANNEL_CREDENTIAL'::character varying, 'BILLING_ACCOUNT_GROUP'::character varying, 'QUOTA_SCOPE'::character varying, 'ROUTE_TARGET'::character varying, 'PROVIDER_CHANNEL_MODEL'::character varying, 'PROVIDER_CHANNEL'::character varying])::text[]))),
    CONSTRAINT chk_upstream_runtime_state CHECK (((runtime_state)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'AUTH_FAILED'::character varying, 'BILLING_EXHAUSTED'::character varying, 'RATE_LIMITED'::character varying, 'MODEL_MAPPING_INVALID'::character varying, 'PERMISSION_DENIED'::character varying, 'QUARANTINED'::character varying])::text[])))
);


--
-- Name: TABLE upstream_runtime_resource_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.upstream_runtime_resource_state IS '上游资源运行时可用状态：由 Gateway 故障事件和管理员恢复操作更新，再经 Outbox 投影为 Redis Snapshot';


--
-- Name: COLUMN upstream_runtime_resource_state.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.tenant_id IS '状态所属租户；字符串 Scope 以租户前缀隔离，数字 ID Scope 仍保留租户便于审计';


--
-- Name: COLUMN upstream_runtime_resource_state.scope_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.scope_type IS '状态作用域类型，与 Gateway FailureScope 对齐';


--
-- Name: COLUMN upstream_runtime_resource_state.scope_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.scope_key IS '状态作用域安全键；数字资源使用 ID 字符串，quotaScope/billingAccountGroup 使用 tenantId:scopeValue';


--
-- Name: COLUMN upstream_runtime_resource_state.runtime_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.runtime_state IS '运行时状态：AVAILABLE 可调度；其他状态由 Snapshot 构建器决定是否参与调度或目录展示';


--
-- Name: COLUMN upstream_runtime_resource_state.last_failure_kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.last_failure_kind IS '最近一次导致状态变化的失败分类';


--
-- Name: COLUMN upstream_runtime_resource_state.last_failed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.last_failed_at IS '最近一次失败发生时间';


--
-- Name: COLUMN upstream_runtime_resource_state.cooldown_until; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.cooldown_until IS '冷却结束时间；短期状态到期后 Gateway 可重新选择，长期状态需管理员或新快照恢复';


--
-- Name: COLUMN upstream_runtime_resource_state.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.upstream_runtime_resource_state.updated_at IS '状态最后更新时间';


--
-- Name: upstream_runtime_resource_state_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.upstream_runtime_resource_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: upstream_runtime_resource_state_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.upstream_runtime_resource_state_id_seq OWNED BY public.upstream_runtime_resource_state.id;


--
-- Name: user_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_account (
    id bigint NOT NULL,
    username character varying(64) NOT NULL,
    password_hash character varying(256) NOT NULL,
    display_name character varying(128),
    email character varying(256),
    scope_type character varying(32) NOT NULL,
    tenant_id bigint,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: TABLE user_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_account IS '用户账号表：记录所有用户的登录凭证与归属，支持平台级和租户级双作用域';


--
-- Name: COLUMN user_account.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.id IS '主键，自增序列';


--
-- Name: COLUMN user_account.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.username IS '用户名，全局唯一，用于登录认证';


--
-- Name: COLUMN user_account.password_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.password_hash IS '密码哈希：BCrypt 加密存储，绝不以明文入库或传输';


--
-- Name: COLUMN user_account.display_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.display_name IS '显示名称，用于界面展示';


--
-- Name: COLUMN user_account.email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.email IS '邮箱地址，可选';


--
-- Name: COLUMN user_account.scope_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.scope_type IS '用户作用域：PLATFORM 平台级用户 / TENANT 租户级用户';


--
-- Name: COLUMN user_account.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.tenant_id IS '所属租户 ID：仅租户级用户有值，平台级用户为 NULL';


--
-- Name: COLUMN user_account.enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.enabled IS '账号启用状态：false 表示已停用，无法登录';


--
-- Name: COLUMN user_account.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.created_at IS '创建时间';


--
-- Name: COLUMN user_account.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.updated_at IS '最后更新时间';


--
-- Name: COLUMN user_account.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_account.deleted_at IS '逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已被管理员软删除，账号不可登录、不可被任何受保护接口认定为有效用户';


--
-- Name: user_account_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_account_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_account_id_seq OWNED BY public.user_account.id;


--
-- Name: user_credit_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_credit_account (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    balance numeric(24,8) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    currency_code character varying(3) DEFAULT 'CNY'::character varying NOT NULL
);


--
-- Name: TABLE user_credit_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_credit_account IS '用户额度账户：与 TENANT 作用域用户一对一对应；余额是模型请求准入和结算的唯一金额事实源';


--
-- Name: COLUMN user_credit_account.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.id IS '主键，自增序列';


--
-- Name: COLUMN user_credit_account.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.tenant_id IS '所属租户：方便按租户聚合统计与跨租户隔离';


--
-- Name: COLUMN user_credit_account.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.user_id IS '所属用户：与 user_account.id 一对一；通过部分唯一索引保证';


--
-- Name: COLUMN user_credit_account.balance; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.balance IS '用户余额，CNY 固定 8 位小数原子精度；1 CNY 等于 100000000 原子单位，不丢失模型计费残差';


--
-- Name: COLUMN user_credit_account.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.created_at IS '账户创建时间';


--
-- Name: COLUMN user_credit_account.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.updated_at IS '最后更新时间（每次余额调整后更新）';


--
-- Name: COLUMN user_credit_account.currency_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_credit_account.currency_code IS '余额账户币种；历史账户兼容回填为 CNY，余额与币种不可混算';


--
-- Name: user_credit_account_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_credit_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_credit_account_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_credit_account_id_seq OWNED BY public.user_credit_account.id;


--
-- Name: user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_role (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE user_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_role IS '用户角色关联表：记录用户与角色的多对多映射关系';


--
-- Name: COLUMN user_role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_role.id IS '主键，自增序列';


--
-- Name: COLUMN user_role.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_role.user_id IS '用户 ID，引用 user_account 表';


--
-- Name: COLUMN user_role.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_role.role_id IS '角色 ID，引用 role 表，用户通过角色间接获得权限';


--
-- Name: COLUMN user_role.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_role.created_at IS '分配时间';


--
-- Name: user_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_role_id_seq OWNED BY public.user_role.id;


--
-- Name: api_key id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key ALTER COLUMN id SET DEFAULT nextval('public.api_key_id_seq'::regclass);


--
-- Name: billing_settlement id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement ALTER COLUMN id SET DEFAULT nextval('public.billing_settlement_id_seq'::regclass);


--
-- Name: credit_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction ALTER COLUMN id SET DEFAULT nextval('public.credit_transaction_id_seq'::regclass);


--
-- Name: model_route id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route ALTER COLUMN id SET DEFAULT nextval('public.model_route_id_seq'::regclass);


--
-- Name: permission id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission ALTER COLUMN id SET DEFAULT nextval('public.permission_id_seq'::regclass);


--
-- Name: provider id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider ALTER COLUMN id SET DEFAULT nextval('public.provider_id_seq'::regclass);


--
-- Name: provider_base_url id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_base_url ALTER COLUMN id SET DEFAULT nextval('public.provider_base_url_id_seq'::regclass);


--
-- Name: provider_channel id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel ALTER COLUMN id SET DEFAULT nextval('public.provider_channel_id_seq'::regclass);


--
-- Name: provider_channel_credential id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_credential ALTER COLUMN id SET DEFAULT nextval('public.provider_channel_credential_id_seq'::regclass);


--
-- Name: provider_channel_model id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model ALTER COLUMN id SET DEFAULT nextval('public.provider_channel_model_id_seq'::regclass);


--
-- Name: provider_credential id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential ALTER COLUMN id SET DEFAULT nextval('public.provider_credential_id_seq'::regclass);


--
-- Name: recharge_card id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card ALTER COLUMN id SET DEFAULT nextval('public.recharge_card_id_seq'::regclass);


--
-- Name: recharge_card_batch id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card_batch ALTER COLUMN id SET DEFAULT nextval('public.recharge_card_batch_id_seq'::regclass);


--
-- Name: relay_request_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log ALTER COLUMN id SET DEFAULT nextval('public.relay_request_log_id_seq'::regclass);


--
-- Name: role id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role ALTER COLUMN id SET DEFAULT nextval('public.role_id_seq'::regclass);


--
-- Name: role_permission id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission ALTER COLUMN id SET DEFAULT nextval('public.role_permission_id_seq'::regclass);


--
-- Name: route_target id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target ALTER COLUMN id SET DEFAULT nextval('public.route_target_id_seq'::regclass);


--
-- Name: runtime_outbox id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_outbox ALTER COLUMN id SET DEFAULT nextval('public.runtime_outbox_id_seq'::regclass);


--
-- Name: tenant id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant ALTER COLUMN id SET DEFAULT nextval('public.tenant_id_seq'::regclass);


--
-- Name: tenant_model id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model ALTER COLUMN id SET DEFAULT nextval('public.tenant_model_id_seq'::regclass);


--
-- Name: tenant_model_candidate_mapping id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping ALTER COLUMN id SET DEFAULT nextval('public.tenant_model_candidate_mapping_id_seq'::regclass);


--
-- Name: tenant_model_price id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_price ALTER COLUMN id SET DEFAULT nextval('public.tenant_model_price_id_seq'::regclass);


--
-- Name: upstream_runtime_failure_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_failure_event ALTER COLUMN id SET DEFAULT nextval('public.upstream_runtime_failure_event_id_seq'::regclass);


--
-- Name: upstream_runtime_resource_state id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_resource_state ALTER COLUMN id SET DEFAULT nextval('public.upstream_runtime_resource_state_id_seq'::regclass);


--
-- Name: user_account id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_account ALTER COLUMN id SET DEFAULT nextval('public.user_account_id_seq'::regclass);


--
-- Name: user_credit_account id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_credit_account ALTER COLUMN id SET DEFAULT nextval('public.user_credit_account_id_seq'::regclass);


--
-- Name: user_role id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role ALTER COLUMN id SET DEFAULT nextval('public.user_role_id_seq'::regclass);


--
-- Data for Name: api_key; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: billing_settlement; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: credit_transaction; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: model_route; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: permission; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.permission VALUES (1, 'PLATFORM_ADMIN', '平台管理', '平台级管理权限，包含租户管理、用户管理等全部操作', '2026-06-28 06:34:24.621858+00', '2026-06-28 06:34:24.621858+00');
INSERT INTO public.permission VALUES (2, 'TENANT_ADMIN', '租户管理', '租户级管理权限，管理所属租户内资源', '2026-06-28 06:34:24.621858+00', '2026-06-28 06:34:24.621858+00');
INSERT INTO public.permission VALUES (3, 'TENANT_MEMBER', '租户成员', '租户内普通成员权限，使用租户内资源', '2026-06-28 06:34:24.621858+00', '2026-06-28 06:34:24.621858+00');
INSERT INTO public.permission VALUES (4, 'PLATFORM_CONSOLE_ACCESS', '平台控制台访问', '允许登录并访问平台控制台', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (5, 'TENANT_READ', '查看租户列表', '允许查看租户列表与详情', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (6, 'TENANT_CREATE', '创建租户', '允许创建新租户', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (7, 'TENANT_UPDATE', '编辑租户', '允许编辑租户基础信息', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (8, 'TENANT_ENABLE', '启用租户', '允许启用已停用的租户', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (9, 'TENANT_DISABLE', '停用租户', '允许停用启用的租户', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (10, 'TENANT_DELETE', '删除租户', '允许逻辑删除租户', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (11, 'TENANT_EXPIRE_SET', '设置租户过期', '允许设置或清除租户过期时间', '2026-06-28 06:34:24.654289+00', '2026-06-28 06:34:24.654289+00');
INSERT INTO public.permission VALUES (12, 'MEMBER_READ', '查看租户成员', '允许查看租户成员列表与详情', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (13, 'MEMBER_CREATE', '创建租户成员', '允许在租户内创建新成员', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (14, 'MEMBER_UPDATE', '编辑租户成员', '允许编辑成员基础资料与调整成员角色', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (15, 'MEMBER_ENABLE', '启用成员', '允许启用已停用的租户成员', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (16, 'MEMBER_DISABLE', '停用成员', '允许停用启用中的租户成员', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (17, 'MEMBER_DELETE', '删除成员', '允许软删除租户成员', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (18, 'MEMBER_PASSWORD_RESET', '重置成员密码', '允许由管理员重置租户成员登录密码', '2026-06-28 06:34:24.867428+00', '2026-06-28 06:34:24.867428+00');
INSERT INTO public.permission VALUES (19, 'API_KEY_SELF_MANAGE', '管理自己的 API Key', '允许查看、创建、编辑、启停、删除自己的 API Key', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (20, 'API_KEY_TENANT_MANAGE', '管理本租户 API Key', '允许租户管理员管理本租户全部用户的 API Key', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (21, 'API_KEY_CROSS_TENANT_MANAGE', '跨租户管理 API Key', '允许平台管理员跨租户查询与管理所有 API Key', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (22, 'CREDIT_SELF_READ', '查看自己的额度', '允许查看自己的额度账户与流水', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (23, 'CREDIT_TENANT_READ', '查看本租户额度', '允许查看本租户全部用户的额度账户与流水', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (24, 'CREDIT_TENANT_ADJUST', '调整本租户额度', '允许为本租户用户增加或扣减额度', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (25, 'CREDIT_CROSS_TENANT_ADJUST', '跨租户调整额度', '允许平台管理员跨租户查看与调整用户额度', '2026-06-28 06:34:25.017897+00', '2026-06-28 06:34:25.017897+00');
INSERT INTO public.permission VALUES (26, 'CARD_SELF_REDEEM', '核销卡密充值', '允许租户用户核销卡密并增加自己的额度', '2026-06-28 06:34:25.174241+00', '2026-06-28 06:34:25.174241+00');
INSERT INTO public.permission VALUES (27, 'CARD_TENANT_MANAGE', '管理本租户卡密', '允许租户管理员创建批次、查看卡密、停用启用', '2026-06-28 06:34:25.174241+00', '2026-06-28 06:34:25.174241+00');
INSERT INTO public.permission VALUES (28, 'CARD_CROSS_TENANT_MANAGE', '跨租户管理卡密', '允许平台管理员跨租户管理卡密批次', '2026-06-28 06:34:25.174241+00', '2026-06-28 06:34:25.174241+00');
INSERT INTO public.permission VALUES (29, 'CARD_RECORD_READ_TENANT', '查看本租户充值流水', '允许查看本租户的卡密充值记录', '2026-06-28 06:34:25.174241+00', '2026-06-28 06:34:25.174241+00');
INSERT INTO public.permission VALUES (30, 'UPSTREAM_READ', '查看上游配置', '允许查看可见上游厂商、接入地址、通道和脱敏凭证元数据', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (31, 'UPSTREAM_CREATE', '创建上游配置', '允许创建私有厂商、接入地址、通道和凭证', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (32, 'UPSTREAM_UPDATE', '编辑上游配置', '允许编辑可管理资源的基础资料和运行参数', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (33, 'UPSTREAM_ENABLE', '启用上游配置', '允许启用停用资源', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (34, 'UPSTREAM_DISABLE', '停用上游配置', '允许停用资源', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (35, 'UPSTREAM_DELETE', '删除上游配置', '允许逻辑删除未受引用保护的资源', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (36, 'UPSTREAM_CROSS_TENANT_MANAGE', '跨租户管理上游配置', '允许平台管理员查看和管理全部租户上游配置', '2026-06-28 06:34:25.316716+00', '2026-06-28 06:34:25.316716+00');
INSERT INTO public.permission VALUES (41, 'TENANT_MODEL_READ', '查看本租户模型', '查看当前租户的模型、候选映射、价格、路由与路由目标', '2026-06-28 06:34:26.150404+00', '2026-06-28 06:34:26.150404+00');
INSERT INTO public.permission VALUES (42, 'TENANT_MODEL_MANAGE', '管理本租户模型', '创建、编辑、启停、删除当前租户的模型、候选映射、价格、路由与路由目标', '2026-06-28 06:34:26.150404+00', '2026-06-28 06:34:26.150404+00');
INSERT INTO public.permission VALUES (43, 'TENANT_MODEL_CROSS_TENANT_MANAGE', '跨租户管理模型', '平台管理员显式指定目标租户后管理任意租户的模型领域资源', '2026-06-28 06:34:26.150404+00', '2026-06-28 06:34:26.150404+00');
INSERT INTO public.permission VALUES (44, 'TENANT_MODEL_PUBLIC_READ', '查看可用模型目录', '查看当前租户对 C 端可见的模型与对外价格', '2026-06-28 06:34:26.150404+00', '2026-06-28 06:34:26.150404+00');


--
-- Data for Name: provider; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_base_url; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_channel; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_channel_credential; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_channel_model; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: provider_credential; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: recharge_card; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: recharge_card_batch; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: relay_event_receipt; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: relay_request_log; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: role; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.role VALUES (1, 'PLATFORM_ADMIN', '平台管理员', '平台超级管理员，管理所有租户与平台配置', 'PLATFORM', '2026-06-28 06:34:24.625598+00', '2026-06-28 06:34:24.625598+00');
INSERT INTO public.role VALUES (2, 'TENANT_ADMIN', '租户管理员', '租户管理员，管理所属租户内用户与资源', 'TENANT', '2026-06-28 06:34:24.625598+00', '2026-06-28 06:34:24.625598+00');
INSERT INTO public.role VALUES (3, 'TENANT_MEMBER', '租户成员', '租户成员，使用租户内分配的 AI 资源', 'TENANT', '2026-06-28 06:34:24.625598+00', '2026-06-28 06:34:24.625598+00');


--
-- Data for Name: role_permission; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.role_permission VALUES (1, 1, 1, '2026-06-28 06:34:24.62919+00');
INSERT INTO public.role_permission VALUES (2, 2, 2, '2026-06-28 06:34:24.63661+00');
INSERT INTO public.role_permission VALUES (3, 3, 3, '2026-06-28 06:34:24.639459+00');
INSERT INTO public.role_permission VALUES (5, 1, 4, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (6, 1, 5, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (7, 1, 6, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (8, 1, 7, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (9, 1, 8, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (10, 1, 9, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (11, 1, 10, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (12, 1, 11, '2026-06-28 06:34:24.65765+00');
INSERT INTO public.role_permission VALUES (13, 2, 4, '2026-06-28 06:34:24.663036+00');
INSERT INTO public.role_permission VALUES (14, 3, 4, '2026-06-28 06:34:24.666188+00');
INSERT INTO public.role_permission VALUES (15, 1, 12, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (16, 2, 12, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (17, 1, 13, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (18, 2, 13, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (19, 1, 14, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (20, 2, 14, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (21, 1, 15, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (22, 2, 15, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (23, 1, 16, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (24, 2, 16, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (25, 1, 17, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (26, 2, 17, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (27, 1, 18, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (28, 2, 18, '2026-06-28 06:34:24.870505+00');
INSERT INTO public.role_permission VALUES (29, 1, 21, '2026-06-28 06:34:25.020679+00');
INSERT INTO public.role_permission VALUES (30, 1, 25, '2026-06-28 06:34:25.020679+00');
INSERT INTO public.role_permission VALUES (31, 2, 19, '2026-06-28 06:34:25.024156+00');
INSERT INTO public.role_permission VALUES (32, 2, 20, '2026-06-28 06:34:25.024156+00');
INSERT INTO public.role_permission VALUES (33, 2, 22, '2026-06-28 06:34:25.024156+00');
INSERT INTO public.role_permission VALUES (34, 2, 23, '2026-06-28 06:34:25.024156+00');
INSERT INTO public.role_permission VALUES (35, 2, 24, '2026-06-28 06:34:25.024156+00');
INSERT INTO public.role_permission VALUES (36, 3, 19, '2026-06-28 06:34:25.027691+00');
INSERT INTO public.role_permission VALUES (37, 3, 22, '2026-06-28 06:34:25.027691+00');
INSERT INTO public.role_permission VALUES (38, 1, 28, '2026-06-28 06:34:25.176573+00');
INSERT INTO public.role_permission VALUES (39, 1, 29, '2026-06-28 06:34:25.176573+00');
INSERT INTO public.role_permission VALUES (40, 2, 26, '2026-06-28 06:34:25.179208+00');
INSERT INTO public.role_permission VALUES (41, 2, 27, '2026-06-28 06:34:25.179208+00');
INSERT INTO public.role_permission VALUES (42, 2, 29, '2026-06-28 06:34:25.179208+00');
INSERT INTO public.role_permission VALUES (43, 3, 26, '2026-06-28 06:34:25.181959+00');
INSERT INTO public.role_permission VALUES (44, 1, 30, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (45, 1, 31, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (46, 1, 32, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (47, 1, 33, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (48, 1, 34, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (49, 1, 35, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (50, 1, 36, '2026-06-28 06:34:25.319484+00');
INSERT INTO public.role_permission VALUES (51, 2, 30, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (52, 2, 31, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (53, 2, 32, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (54, 2, 33, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (55, 2, 34, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (56, 2, 35, '2026-06-28 06:34:25.322949+00');
INSERT INTO public.role_permission VALUES (64, 1, 41, '2026-06-28 06:34:26.153491+00');
INSERT INTO public.role_permission VALUES (65, 1, 42, '2026-06-28 06:34:26.153491+00');
INSERT INTO public.role_permission VALUES (66, 1, 43, '2026-06-28 06:34:26.153491+00');
INSERT INTO public.role_permission VALUES (67, 1, 44, '2026-06-28 06:34:26.153491+00');
INSERT INTO public.role_permission VALUES (68, 2, 41, '2026-06-28 06:34:26.156698+00');
INSERT INTO public.role_permission VALUES (69, 2, 42, '2026-06-28 06:34:26.156698+00');
INSERT INTO public.role_permission VALUES (70, 2, 44, '2026-06-28 06:34:26.156698+00');
INSERT INTO public.role_permission VALUES (71, 3, 44, '2026-06-28 06:34:26.159895+00');


--
-- Data for Name: route_target; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: runtime_outbox; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: runtime_projection_state; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: runtime_snapshot_version; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant_model; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant_model_candidate_mapping; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant_model_price; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: upstream_runtime_failure_event; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: upstream_runtime_resource_state; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: user_account; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: user_credit_account; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: user_role; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Name: api_key_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.api_key_id_seq', 1, false);


--
-- Name: billing_settlement_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.billing_settlement_id_seq', 1, false);


--
-- Name: credit_transaction_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.credit_transaction_id_seq', 1, false);


--
-- Name: model_route_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.model_route_id_seq', 1, false);


--
-- Name: permission_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.permission_id_seq', 44, true);


--
-- Name: provider_base_url_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_base_url_id_seq', 1, false);


--
-- Name: provider_channel_credential_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_channel_credential_id_seq', 1, false);


--
-- Name: provider_channel_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_channel_id_seq', 1, false);


--
-- Name: provider_channel_model_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_channel_model_id_seq', 1, false);


--
-- Name: provider_credential_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_credential_id_seq', 1, false);


--
-- Name: provider_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.provider_id_seq', 1, false);


--
-- Name: recharge_card_batch_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.recharge_card_batch_id_seq', 1, false);


--
-- Name: recharge_card_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.recharge_card_id_seq', 1, false);


--
-- Name: relay_request_log_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.relay_request_log_id_seq', 1, false);


--
-- Name: role_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.role_id_seq', 3, true);


--
-- Name: role_permission_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.role_permission_id_seq', 71, true);


--
-- Name: route_target_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.route_target_id_seq', 1, false);


--
-- Name: runtime_outbox_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.runtime_outbox_id_seq', 1, false);


--
-- Name: tenant_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.tenant_id_seq', 1, false);


--
-- Name: tenant_model_candidate_mapping_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.tenant_model_candidate_mapping_id_seq', 1, false);


--
-- Name: tenant_model_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.tenant_model_id_seq', 1, false);


--
-- Name: tenant_model_price_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.tenant_model_price_id_seq', 1, false);


--
-- Name: upstream_runtime_failure_event_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.upstream_runtime_failure_event_id_seq', 1, false);


--
-- Name: upstream_runtime_resource_state_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.upstream_runtime_resource_state_id_seq', 1, false);


--
-- Name: user_account_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.user_account_id_seq', 1, false);


--
-- Name: user_credit_account_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.user_credit_account_id_seq', 1, false);


--
-- Name: user_role_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.user_role_id_seq', 1, false);


--
-- Name: api_key api_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT api_key_pkey PRIMARY KEY (id);


--
-- Name: billing_settlement billing_settlement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_pkey PRIMARY KEY (id);


--
-- Name: billing_settlement billing_settlement_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_request_id_key UNIQUE (request_id);


--
-- Name: credit_transaction credit_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT credit_transaction_pkey PRIMARY KEY (id);


--
-- Name: model_route model_route_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_pkey PRIMARY KEY (id);


--
-- Name: permission permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission
    ADD CONSTRAINT permission_pkey PRIMARY KEY (id);


--
-- Name: provider_base_url provider_base_url_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_base_url
    ADD CONSTRAINT provider_base_url_pkey PRIMARY KEY (id);


--
-- Name: provider_channel_credential provider_channel_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_credential
    ADD CONSTRAINT provider_channel_credential_pkey PRIMARY KEY (id);


--
-- Name: provider_channel_model provider_channel_model_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model
    ADD CONSTRAINT provider_channel_model_pkey PRIMARY KEY (id);


--
-- Name: provider_channel provider_channel_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel
    ADD CONSTRAINT provider_channel_pkey PRIMARY KEY (id);


--
-- Name: provider_credential provider_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential
    ADD CONSTRAINT provider_credential_pkey PRIMARY KEY (id);


--
-- Name: provider provider_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider
    ADD CONSTRAINT provider_pkey PRIMARY KEY (id);


--
-- Name: recharge_card_batch recharge_card_batch_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card_batch
    ADD CONSTRAINT recharge_card_batch_pkey PRIMARY KEY (id);


--
-- Name: recharge_card recharge_card_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card
    ADD CONSTRAINT recharge_card_pkey PRIMARY KEY (id);


--
-- Name: relay_event_receipt relay_event_receipt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_event_receipt
    ADD CONSTRAINT relay_event_receipt_pkey PRIMARY KEY (event_id);


--
-- Name: relay_request_log relay_request_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_pkey PRIMARY KEY (id);


--
-- Name: relay_request_log relay_request_log_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_request_id_key UNIQUE (request_id);


--
-- Name: role_permission role_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT role_permission_pkey PRIMARY KEY (id);


--
-- Name: role role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (id);


--
-- Name: route_target route_target_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_pkey PRIMARY KEY (id);


--
-- Name: runtime_outbox runtime_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_outbox
    ADD CONSTRAINT runtime_outbox_pkey PRIMARY KEY (id);


--
-- Name: runtime_projection_state runtime_projection_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_projection_state
    ADD CONSTRAINT runtime_projection_state_pkey PRIMARY KEY (state_key);


--
-- Name: runtime_snapshot_version runtime_snapshot_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_snapshot_version
    ADD CONSTRAINT runtime_snapshot_version_pkey PRIMARY KEY (scope_type, scope_key);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_pkey PRIMARY KEY (id);


--
-- Name: tenant_model tenant_model_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model
    ADD CONSTRAINT tenant_model_pkey PRIMARY KEY (id);


--
-- Name: tenant_model_price tenant_model_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_price
    ADD CONSTRAINT tenant_model_price_pkey PRIMARY KEY (id);


--
-- Name: tenant tenant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);


--
-- Name: upstream_runtime_resource_state uk_upstream_runtime_resource_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_resource_state
    ADD CONSTRAINT uk_upstream_runtime_resource_state UNIQUE (scope_type, scope_key);


--
-- Name: upstream_runtime_failure_event upstream_runtime_failure_event_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_failure_event
    ADD CONSTRAINT upstream_runtime_failure_event_event_id_key UNIQUE (event_id);


--
-- Name: upstream_runtime_failure_event upstream_runtime_failure_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_failure_event
    ADD CONSTRAINT upstream_runtime_failure_event_pkey PRIMARY KEY (id);


--
-- Name: upstream_runtime_resource_state upstream_runtime_resource_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upstream_runtime_resource_state
    ADD CONSTRAINT upstream_runtime_resource_state_pkey PRIMARY KEY (id);


--
-- Name: user_account user_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_account
    ADD CONSTRAINT user_account_pkey PRIMARY KEY (id);


--
-- Name: user_credit_account user_credit_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_credit_account
    ADD CONSTRAINT user_credit_account_pkey PRIMARY KEY (id);


--
-- Name: user_role user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (id);


--
-- Name: idx_api_key_runtime_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_runtime_lookup ON public.api_key USING btree (lookup_hash, tenant_id, user_id) WHERE ((deleted_at IS NULL) AND (lookup_hash_version = 1));


--
-- Name: idx_api_key_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_tenant ON public.api_key USING btree (tenant_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_api_key_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_user ON public.api_key USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_batch_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_batch_tenant ON public.recharge_card_batch USING btree (tenant_id, created_at DESC);


--
-- Name: idx_billing_settlement_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_billing_settlement_status_created ON public.billing_settlement USING btree (status, created_at);


--
-- Name: idx_billing_settlement_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_billing_settlement_tenant_created ON public.billing_settlement USING btree (tenant_id, created_at DESC);


--
-- Name: idx_billing_settlement_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_billing_settlement_user_created ON public.billing_settlement USING btree (user_id, created_at DESC);


--
-- Name: idx_card_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_card_batch ON public.recharge_card USING btree (batch_id, status);


--
-- Name: idx_card_redeemed_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_card_redeemed_user ON public.recharge_card USING btree (redeemed_user_id) WHERE (redeemed_user_id IS NOT NULL);


--
-- Name: idx_card_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_card_tenant_status ON public.recharge_card USING btree (tenant_id, status);


--
-- Name: idx_channel_model_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_channel_model_tenant_active ON public.provider_channel_model USING btree (tenant_id, provider_channel_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_credit_account_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_account_tenant ON public.user_credit_account USING btree (tenant_id);


--
-- Name: idx_credit_txn_billing_settlement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_txn_billing_settlement ON public.credit_transaction USING btree (billing_settlement_id) WHERE (billing_settlement_id IS NOT NULL);


--
-- Name: idx_credit_txn_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_txn_tenant_created ON public.credit_transaction USING btree (tenant_id, created_at DESC);


--
-- Name: idx_credit_txn_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_txn_user_created ON public.credit_transaction USING btree (user_id, created_at DESC);


--
-- Name: idx_model_route_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_route_tenant_active ON public.model_route USING btree (tenant_id, tenant_model_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_base_url_provider_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_base_url_provider_status ON public.provider_base_url USING btree (provider_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_channel_base_url_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_channel_base_url_status ON public.provider_channel USING btree (provider_base_url_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_channel_credential_runtime; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_channel_credential_runtime ON public.provider_channel_credential USING btree (provider_channel_id, enabled, provider_credential_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_channel_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_channel_tenant_status ON public.provider_channel USING btree (tenant_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_credential_binding_runtime; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_credential_binding_runtime ON public.provider_channel_credential USING btree (provider_credential_id, enabled, provider_channel_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_credential_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_credential_tenant_status ON public.provider_credential USING btree (tenant_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_provider_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provider_tenant_active ON public.provider USING btree (tenant_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_relay_log_api_key_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_relay_log_api_key_created ON public.relay_request_log USING btree (api_key_id, created_at DESC);


--
-- Name: idx_relay_log_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_relay_log_tenant_created ON public.relay_request_log USING btree (tenant_id, created_at DESC);


--
-- Name: idx_relay_log_tenant_model_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_relay_log_tenant_model_created ON public.relay_request_log USING btree (tenant_id, tenant_model_code, created_at DESC);


--
-- Name: idx_relay_log_tenant_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_relay_log_tenant_status_created ON public.relay_request_log USING btree (tenant_id, request_status, created_at DESC);


--
-- Name: idx_relay_log_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_relay_log_user_created ON public.relay_request_log USING btree (user_id, created_at DESC);


--
-- Name: idx_route_target_mapping_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_route_target_mapping_active ON public.route_target USING btree (tenant_model_candidate_mapping_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_route_target_route_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_route_target_route_enabled ON public.route_target USING btree (model_route_id, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_runtime_outbox_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_outbox_claim ON public.runtime_outbox USING btree (status, next_retry_at, id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY'::character varying])::text[]));


--
-- Name: idx_runtime_outbox_tenant_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_outbox_tenant_pending ON public.runtime_outbox USING btree (tenant_id, occurred_at DESC) WHERE ((status)::text <> 'COMPLETED'::text);


--
-- Name: idx_tenant_model_price_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_model_price_history ON public.tenant_model_price USING btree (tenant_id, tenant_model_id, version);


--
-- Name: idx_tenant_model_visible; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_model_visible ON public.tenant_model USING btree (tenant_id, publish_status, enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_tmcm_tenant_candidate_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tmcm_tenant_candidate_active ON public.tenant_model_candidate_mapping USING btree (tenant_id, provider_channel_model_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_tmcm_tenant_model_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tmcm_tenant_model_active ON public.tenant_model_candidate_mapping USING btree (tenant_id, tenant_model_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_upstream_runtime_failure_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upstream_runtime_failure_tenant_time ON public.upstream_runtime_failure_event USING btree (tenant_id, occurred_at DESC);


--
-- Name: idx_upstream_runtime_state_tenant_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upstream_runtime_state_tenant_scope ON public.upstream_runtime_resource_state USING btree (tenant_id, scope_type, runtime_state);


--
-- Name: idx_user_account_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_account_tenant_id ON public.user_account USING btree (tenant_id) WHERE (deleted_at IS NULL);


--
-- Name: uk_api_key_lookup_hash_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_api_key_lookup_hash_active ON public.api_key USING btree (lookup_hash) WHERE ((deleted_at IS NULL) AND (lookup_hash_version = 1));


--
-- Name: uk_api_key_prefix; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_api_key_prefix ON public.api_key USING btree (key_prefix) WHERE (deleted_at IS NULL);


--
-- Name: uk_batch_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_batch_code ON public.recharge_card_batch USING btree (batch_code);


--
-- Name: uk_card_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_card_hash ON public.recharge_card USING btree (card_hash);


--
-- Name: uk_channel_model_upstream_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_channel_model_upstream_active ON public.provider_channel_model USING btree (provider_channel_id, upstream_model_id) WHERE (deleted_at IS NULL);


--
-- Name: uk_credit_account_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_credit_account_user ON public.user_credit_account USING btree (user_id);


--
-- Name: uk_credit_txn_card; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_credit_txn_card ON public.credit_transaction USING btree (card_id) WHERE ((source)::text = 'CARD_REDEEM'::text);


--
-- Name: uk_model_route_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_model_route_active ON public.model_route USING btree (tenant_model_id, inbound_protocol) WHERE (deleted_at IS NULL);


--
-- Name: uk_permission_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_permission_code ON public.permission USING btree (code);


--
-- Name: uk_provider_base_url_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_provider_base_url_active ON public.provider_base_url USING btree (provider_id, protocol, normalized_base_url) WHERE (deleted_at IS NULL);


--
-- Name: uk_provider_channel_credential_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_provider_channel_credential_active ON public.provider_channel_credential USING btree (provider_channel_id, provider_credential_id) WHERE (deleted_at IS NULL);


--
-- Name: uk_provider_code_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_provider_code_active ON public.provider USING btree (code) WHERE (deleted_at IS NULL);


--
-- Name: uk_provider_credential_tenant_fingerprint_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_provider_credential_tenant_fingerprint_active ON public.provider_credential USING btree (tenant_id, credential_fingerprint) WHERE (deleted_at IS NULL);


--
-- Name: uk_role_code_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_role_code_scope ON public.role USING btree (code, scope_type);


--
-- Name: uk_role_permission; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_role_permission ON public.role_permission USING btree (role_id, permission_id);


--
-- Name: uk_route_target_mapping_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_route_target_mapping_active ON public.route_target USING btree (model_route_id, tenant_model_candidate_mapping_id) WHERE (deleted_at IS NULL);


--
-- Name: uk_tenant_model_code_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_tenant_model_code_active ON public.tenant_model USING btree (tenant_id, model_code) WHERE (deleted_at IS NULL);


--
-- Name: uk_tenant_model_price_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_tenant_model_price_current ON public.tenant_model_price USING btree (tenant_model_id) WHERE (expired_at IS NULL);


--
-- Name: uk_tenant_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_tenant_tenant_code ON public.tenant USING btree (tenant_code) WHERE (deleted_at IS NULL);


--
-- Name: uk_tmcm_pair_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_tmcm_pair_active ON public.tenant_model_candidate_mapping USING btree (tenant_model_id, provider_channel_model_id) WHERE (deleted_at IS NULL);


--
-- Name: uk_user_account_username; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_account_username ON public.user_account USING btree (username) WHERE (deleted_at IS NULL);


--
-- Name: uk_user_role; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_role ON public.user_role USING btree (user_id, role_id);


--
-- Name: api_key api_key_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT api_key_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: api_key api_key_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT api_key_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);


--
-- Name: billing_settlement billing_settlement_api_key_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_api_key_id_fkey FOREIGN KEY (api_key_id) REFERENCES public.api_key(id);


--
-- Name: billing_settlement billing_settlement_reconciled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_reconciled_by_fkey FOREIGN KEY (reconciled_by) REFERENCES public.user_account(id);


--
-- Name: billing_settlement billing_settlement_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: billing_settlement billing_settlement_tenant_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_tenant_model_id_fkey FOREIGN KEY (tenant_model_id) REFERENCES public.tenant_model(id);


--
-- Name: billing_settlement billing_settlement_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_settlement
    ADD CONSTRAINT billing_settlement_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);


--
-- Name: credit_transaction credit_transaction_card_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT credit_transaction_card_id_fkey FOREIGN KEY (card_id) REFERENCES public.recharge_card(id);


--
-- Name: credit_transaction credit_transaction_operator_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT credit_transaction_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES public.user_account(id);


--
-- Name: credit_transaction credit_transaction_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT credit_transaction_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: credit_transaction credit_transaction_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT credit_transaction_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);


--
-- Name: credit_transaction fk_credit_transaction_billing_settlement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_transaction
    ADD CONSTRAINT fk_credit_transaction_billing_settlement FOREIGN KEY (billing_settlement_id) REFERENCES public.billing_settlement(id);


--
-- Name: model_route model_route_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: model_route model_route_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: model_route model_route_tenant_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_tenant_model_id_fkey FOREIGN KEY (tenant_model_id) REFERENCES public.tenant_model(id);


--
-- Name: model_route model_route_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.user_account(id);


--
-- Name: provider_base_url provider_base_url_provider_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_base_url
    ADD CONSTRAINT provider_base_url_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES public.provider(id);


--
-- Name: provider_channel_credential provider_channel_credential_provider_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_credential
    ADD CONSTRAINT provider_channel_credential_provider_channel_id_fkey FOREIGN KEY (provider_channel_id) REFERENCES public.provider_channel(id);


--
-- Name: provider_channel_credential provider_channel_credential_provider_credential_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_credential
    ADD CONSTRAINT provider_channel_credential_provider_credential_id_fkey FOREIGN KEY (provider_credential_id) REFERENCES public.provider_credential(id);


--
-- Name: provider_channel_credential provider_channel_credential_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_credential
    ADD CONSTRAINT provider_channel_credential_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: provider_channel_model provider_channel_model_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model
    ADD CONSTRAINT provider_channel_model_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: provider_channel_model provider_channel_model_provider_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model
    ADD CONSTRAINT provider_channel_model_provider_channel_id_fkey FOREIGN KEY (provider_channel_id) REFERENCES public.provider_channel(id);


--
-- Name: provider_channel_model provider_channel_model_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model
    ADD CONSTRAINT provider_channel_model_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: provider_channel_model provider_channel_model_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel_model
    ADD CONSTRAINT provider_channel_model_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.user_account(id);


--
-- Name: provider_channel provider_channel_provider_base_url_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel
    ADD CONSTRAINT provider_channel_provider_base_url_id_fkey FOREIGN KEY (provider_base_url_id) REFERENCES public.provider_base_url(id);


--
-- Name: provider_channel provider_channel_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_channel
    ADD CONSTRAINT provider_channel_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: provider_credential provider_credential_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential
    ADD CONSTRAINT provider_credential_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: provider provider_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider
    ADD CONSTRAINT provider_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: recharge_card_batch recharge_card_batch_created_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card_batch
    ADD CONSTRAINT recharge_card_batch_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES public.user_account(id);


--
-- Name: recharge_card recharge_card_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card
    ADD CONSTRAINT recharge_card_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.recharge_card_batch(id);


--
-- Name: recharge_card_batch recharge_card_batch_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card_batch
    ADD CONSTRAINT recharge_card_batch_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: recharge_card recharge_card_redeemed_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card
    ADD CONSTRAINT recharge_card_redeemed_user_id_fkey FOREIGN KEY (redeemed_user_id) REFERENCES public.user_account(id);


--
-- Name: recharge_card recharge_card_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recharge_card
    ADD CONSTRAINT recharge_card_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: relay_request_log relay_request_log_api_key_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_api_key_id_fkey FOREIGN KEY (api_key_id) REFERENCES public.api_key(id);


--
-- Name: relay_request_log relay_request_log_provider_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_provider_channel_id_fkey FOREIGN KEY (provider_channel_id) REFERENCES public.provider_channel(id);


--
-- Name: relay_request_log relay_request_log_provider_channel_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_provider_channel_model_id_fkey FOREIGN KEY (provider_channel_model_id) REFERENCES public.provider_channel_model(id);


--
-- Name: relay_request_log relay_request_log_route_target_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_route_target_id_fkey FOREIGN KEY (route_target_id) REFERENCES public.route_target(id);


--
-- Name: relay_request_log relay_request_log_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: relay_request_log relay_request_log_tenant_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_tenant_model_id_fkey FOREIGN KEY (tenant_model_id) REFERENCES public.tenant_model(id);


--
-- Name: relay_request_log relay_request_log_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_request_log
    ADD CONSTRAINT relay_request_log_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);


--
-- Name: role_permission role_permission_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT role_permission_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permission(id);


--
-- Name: role_permission role_permission_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT role_permission_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.role(id);


--
-- Name: route_target route_target_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: route_target route_target_model_route_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_model_route_id_fkey FOREIGN KEY (model_route_id) REFERENCES public.model_route(id);


--
-- Name: route_target route_target_provider_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_provider_channel_id_fkey FOREIGN KEY (provider_channel_id) REFERENCES public.provider_channel(id);


--
-- Name: route_target route_target_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: route_target route_target_tenant_model_candidate_mapping_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_tenant_model_candidate_mapping_id_fkey FOREIGN KEY (tenant_model_candidate_mapping_id) REFERENCES public.tenant_model_candidate_mapping(id);


--
-- Name: route_target route_target_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.route_target
    ADD CONSTRAINT route_target_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.user_account(id);


--
-- Name: runtime_outbox runtime_outbox_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_outbox
    ADD CONSTRAINT runtime_outbox_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_provider_channel_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_provider_channel_model_id_fkey FOREIGN KEY (provider_channel_model_id) REFERENCES public.provider_channel_model(id);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_tenant_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_tenant_model_id_fkey FOREIGN KEY (tenant_model_id) REFERENCES public.tenant_model(id);


--
-- Name: tenant_model_candidate_mapping tenant_model_candidate_mapping_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_candidate_mapping
    ADD CONSTRAINT tenant_model_candidate_mapping_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.user_account(id);


--
-- Name: tenant_model tenant_model_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model
    ADD CONSTRAINT tenant_model_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: tenant_model_price tenant_model_price_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_price
    ADD CONSTRAINT tenant_model_price_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.user_account(id);


--
-- Name: tenant_model_price tenant_model_price_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_price
    ADD CONSTRAINT tenant_model_price_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_model_price tenant_model_price_tenant_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model_price
    ADD CONSTRAINT tenant_model_price_tenant_model_id_fkey FOREIGN KEY (tenant_model_id) REFERENCES public.tenant_model(id);


--
-- Name: tenant_model tenant_model_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model
    ADD CONSTRAINT tenant_model_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_model tenant_model_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_model
    ADD CONSTRAINT tenant_model_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.user_account(id);


--
-- Name: user_credit_account user_credit_account_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_credit_account
    ADD CONSTRAINT user_credit_account_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: user_credit_account user_credit_account_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_credit_account
    ADD CONSTRAINT user_credit_account_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);


--
-- Name: user_role user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.role(id);


--
-- Name: user_role user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.user_account(id);



