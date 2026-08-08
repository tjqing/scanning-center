CREATE TABLE IF NOT EXISTS scan_rule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, rule_code VARCHAR(64) NOT NULL, rule_name VARCHAR(128) NOT NULL,
 description VARCHAR(1000), rule_type VARCHAR(20) NOT NULL, target_type VARCHAR(20) NOT NULL,
 risk_level VARCHAR(20) NOT NULL, match_type VARCHAR(20), match_content CLOB, case_sensitive BOOLEAN DEFAULT FALSE,
 file_types VARCHAR(500), exclude_patterns VARCHAR(1000), issue_description VARCHAR(1000), suggestion VARCHAR(1000),
 prompt_content CLOB, enabled BOOLEAN DEFAULT TRUE, deleted BOOLEAN DEFAULT FALSE,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_scan_rule_code UNIQUE(rule_code)
);
CREATE TABLE IF NOT EXISTS system_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL, display_name VARCHAR(128) NOT NULL,
 role_code VARCHAR(20) NOT NULL, phone VARCHAR(32), email VARCHAR(128), description VARCHAR(500),
 enabled BOOLEAN DEFAULT TRUE, deleted BOOLEAN DEFAULT FALSE,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_query ON system_user(deleted,enabled,role_code);
CREATE TABLE IF NOT EXISTS code_repository (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_name VARCHAR(128) NOT NULL, source_type VARCHAR(20) NOT NULL,
 description VARCHAR(1000), repository_url VARCHAR(1000), username VARCHAR(128), encrypted_token VARCHAR(2000),
 default_branch VARCHAR(128), scan_paths VARCHAR(1000), exclude_patterns VARCHAR(1000), file_types VARCHAR(500),
 storage_key VARCHAR(500), original_file_name VARCHAR(255), enabled BOOLEAN DEFAULT TRUE, deleted BOOLEAN DEFAULT FALSE,
 last_scan_time TIMESTAMP, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS database_url VARCHAR(1000);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS database_username VARCHAR(128);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS encrypted_database_password VARCHAR(2000);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS document_query VARCHAR(4000);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS document_name_column VARCHAR(128);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS document_content_column VARCHAR(128);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS document_type_column VARCHAR(128);
CREATE TABLE IF NOT EXISTS scan_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_no VARCHAR(64) NOT NULL, task_name VARCHAR(128) NOT NULL,
 description VARCHAR(1000), repository_id BIGINT NOT NULL, scope_json CLOB, status VARCHAR(30) NOT NULL,
 total_files INT DEFAULT 0, completed_files INT DEFAULT 0, success_files INT DEFAULT 0, failed_files INT DEFAULT 0,
 issue_count INT DEFAULT 0, cancel_requested BOOLEAN DEFAULT FALSE, error_message VARCHAR(2000),
 start_time TIMESTAMP, end_time TIMESTAMP, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_scan_task_no UNIQUE(task_no)
);
CREATE TABLE IF NOT EXISTS task_rule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, rule_id BIGINT NOT NULL, rule_snapshot CLOB,
 CONSTRAINT uk_task_rule UNIQUE(task_id, rule_id)
);
CREATE TABLE IF NOT EXISTS task_file (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, relative_path VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL, error_message VARCHAR(2000), issue_count INT DEFAULT 0,
 start_time TIMESTAMP, end_time TIMESTAMP
);
CREATE TABLE IF NOT EXISTS scan_result (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, scanned_files INT DEFAULT 0,
 success_files INT DEFAULT 0, failed_files INT DEFAULT 0, issue_count INT DEFAULT 0,
 high_count INT DEFAULT 0, medium_count INT DEFAULT 0, low_count INT DEFAULT 0, info_count INT DEFAULT 0,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uk_scan_result_task UNIQUE(task_id)
);
CREATE TABLE IF NOT EXISTS scan_issue (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, result_id BIGINT NOT NULL, task_id BIGINT NOT NULL, repository_id BIGINT NOT NULL,
 rule_id BIGINT NOT NULL, title VARCHAR(500) NOT NULL, risk_level VARCHAR(20) NOT NULL, rule_type VARCHAR(20) NOT NULL,
 file_path VARCHAR(1000), start_line INT, end_line INT, matched_content CLOB, context_content CLOB,
 issue_description VARCHAR(2000), suggestion VARCHAR(2000), status VARCHAR(20) DEFAULT 'PENDING',
 handle_comment VARCHAR(2000), handler VARCHAR(128), handle_time TIMESTAMP, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_status ON scan_task(status, create_time);
CREATE INDEX IF NOT EXISTS idx_issue_query ON scan_issue(repository_id, status, create_time);
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS operator_user_id BIGINT;
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS operator_user_name VARCHAR(128);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS operator_user_id BIGINT;
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS operator_user_name VARCHAR(128);
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS operator_user_id BIGINT;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS operator_user_name VARCHAR(128);
ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS operator_user_id BIGINT;
ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS operator_user_name VARCHAR(128);
ALTER TABLE scan_issue ADD COLUMN IF NOT EXISTS operator_user_id BIGINT;
ALTER TABLE scan_issue ADD COLUMN IF NOT EXISTS operator_user_name VARCHAR(128);

-- 表中文名称及说明
COMMENT ON TABLE scan_rule IS '扫描规则：定义代码或文档扫描时使用的检查规则';
COMMENT ON TABLE system_user IS '系统用户：保存系统登录用户及其基本信息';
COMMENT ON TABLE code_repository IS '代码仓库：保存待扫描代码、压缩包或数据库文档源配置';
COMMENT ON TABLE scan_task IS '扫描任务：记录一次扫描任务的配置、进度和状态';
COMMENT ON TABLE task_rule IS '任务规则关联：保存扫描任务使用的规则及规则快照';
COMMENT ON TABLE task_file IS '任务文件：记录扫描任务中单个文件的执行情况';
COMMENT ON TABLE scan_result IS '扫描结果汇总：保存扫描任务的统计结果';
COMMENT ON TABLE scan_issue IS '扫描问题：保存扫描发现的具体问题及处理情况';

-- 扫描规则字段中文名称及说明
COMMENT ON COLUMN scan_rule.id IS '规则ID：主键';
COMMENT ON COLUMN scan_rule.rule_code IS '规则编码：规则唯一标识';
COMMENT ON COLUMN scan_rule.rule_name IS '规则名称';
COMMENT ON COLUMN scan_rule.description IS '规则描述';
COMMENT ON COLUMN scan_rule.rule_type IS '规则类型';
COMMENT ON COLUMN scan_rule.target_type IS '扫描目标类型';
COMMENT ON COLUMN scan_rule.risk_level IS '风险等级';
COMMENT ON COLUMN scan_rule.match_type IS '匹配方式';
COMMENT ON COLUMN scan_rule.match_content IS '匹配内容';
COMMENT ON COLUMN scan_rule.case_sensitive IS '是否区分大小写';
COMMENT ON COLUMN scan_rule.file_types IS '适用文件类型';
COMMENT ON COLUMN scan_rule.exclude_patterns IS '排除模式';
COMMENT ON COLUMN scan_rule.issue_description IS '问题说明';
COMMENT ON COLUMN scan_rule.suggestion IS '整改建议';
COMMENT ON COLUMN scan_rule.prompt_content IS '提示词内容';
COMMENT ON COLUMN scan_rule.enabled IS '是否启用';
COMMENT ON COLUMN scan_rule.deleted IS '是否删除';
COMMENT ON COLUMN scan_rule.create_time IS '创建时间';
COMMENT ON COLUMN scan_rule.update_time IS '更新时间';
COMMENT ON COLUMN scan_rule.operator_user_id IS '操作用户ID';
COMMENT ON COLUMN scan_rule.operator_user_name IS '操作用户名称';

-- 系统用户字段中文名称及说明
COMMENT ON COLUMN system_user.id IS '用户ID：主键';
COMMENT ON COLUMN system_user.username IS '用户名';
COMMENT ON COLUMN system_user.display_name IS '显示名称';
COMMENT ON COLUMN system_user.role_code IS '角色编码';
COMMENT ON COLUMN system_user.phone IS '手机号码';
COMMENT ON COLUMN system_user.email IS '电子邮箱';
COMMENT ON COLUMN system_user.description IS '用户描述';
COMMENT ON COLUMN system_user.enabled IS '是否启用';
COMMENT ON COLUMN system_user.deleted IS '是否删除';
COMMENT ON COLUMN system_user.create_time IS '创建时间';
COMMENT ON COLUMN system_user.update_time IS '更新时间';

-- 代码仓库字段中文名称及说明
COMMENT ON COLUMN code_repository.id IS '仓库ID：主键';
COMMENT ON COLUMN code_repository.repository_name IS '仓库名称';
COMMENT ON COLUMN code_repository.source_type IS '数据源类型';
COMMENT ON COLUMN code_repository.description IS '仓库描述';
COMMENT ON COLUMN code_repository.repository_url IS '仓库地址';
COMMENT ON COLUMN code_repository.username IS '仓库访问用户名';
COMMENT ON COLUMN code_repository.encrypted_token IS '加密访问令牌';
COMMENT ON COLUMN code_repository.default_branch IS '默认分支';
COMMENT ON COLUMN code_repository.scan_paths IS '扫描路径';
COMMENT ON COLUMN code_repository.exclude_patterns IS '排除模式';
COMMENT ON COLUMN code_repository.file_types IS '扫描文件类型';
COMMENT ON COLUMN code_repository.storage_key IS '存储标识';
COMMENT ON COLUMN code_repository.original_file_name IS '原始文件名';
COMMENT ON COLUMN code_repository.enabled IS '是否启用';
COMMENT ON COLUMN code_repository.deleted IS '是否删除';
COMMENT ON COLUMN code_repository.last_scan_time IS '最后扫描时间';
COMMENT ON COLUMN code_repository.create_time IS '创建时间';
COMMENT ON COLUMN code_repository.update_time IS '更新时间';
COMMENT ON COLUMN code_repository.database_url IS '数据库连接地址';
COMMENT ON COLUMN code_repository.database_username IS '数据库用户名';
COMMENT ON COLUMN code_repository.encrypted_database_password IS '加密数据库密码';
COMMENT ON COLUMN code_repository.document_query IS '文档查询语句';
COMMENT ON COLUMN code_repository.document_name_column IS '文档名称字段';
COMMENT ON COLUMN code_repository.document_content_column IS '文档内容字段';
COMMENT ON COLUMN code_repository.document_type_column IS '文档类型字段';
COMMENT ON COLUMN code_repository.operator_user_id IS '操作用户ID';
COMMENT ON COLUMN code_repository.operator_user_name IS '操作用户名称';

-- 扫描任务字段中文名称及说明
COMMENT ON COLUMN scan_task.id IS '任务ID：主键';
COMMENT ON COLUMN scan_task.task_no IS '任务编号：任务唯一标识';
COMMENT ON COLUMN scan_task.task_name IS '任务名称';
COMMENT ON COLUMN scan_task.description IS '任务描述';
COMMENT ON COLUMN scan_task.repository_id IS '仓库ID';
COMMENT ON COLUMN scan_task.scope_json IS '扫描范围配置JSON';
COMMENT ON COLUMN scan_task.status IS '任务状态';
COMMENT ON COLUMN scan_task.total_files IS '文件总数';
COMMENT ON COLUMN scan_task.completed_files IS '已完成文件数';
COMMENT ON COLUMN scan_task.success_files IS '成功文件数';
COMMENT ON COLUMN scan_task.failed_files IS '失败文件数';
COMMENT ON COLUMN scan_task.issue_count IS '问题数量';
COMMENT ON COLUMN scan_task.cancel_requested IS '是否请求取消';
COMMENT ON COLUMN scan_task.error_message IS '错误信息';
COMMENT ON COLUMN scan_task.start_time IS '开始时间';
COMMENT ON COLUMN scan_task.end_time IS '结束时间';
COMMENT ON COLUMN scan_task.create_time IS '创建时间';
COMMENT ON COLUMN scan_task.update_time IS '更新时间';
COMMENT ON COLUMN scan_task.operator_user_id IS '操作用户ID';
COMMENT ON COLUMN scan_task.operator_user_name IS '操作用户名称';

-- 任务规则关联字段中文名称及说明
COMMENT ON COLUMN task_rule.id IS '关联ID：主键';
COMMENT ON COLUMN task_rule.task_id IS '任务ID';
COMMENT ON COLUMN task_rule.rule_id IS '规则ID';
COMMENT ON COLUMN task_rule.rule_snapshot IS '规则快照';

-- 任务文件字段中文名称及说明
COMMENT ON COLUMN task_file.id IS '文件记录ID：主键';
COMMENT ON COLUMN task_file.task_id IS '任务ID';
COMMENT ON COLUMN task_file.relative_path IS '文件相对路径';
COMMENT ON COLUMN task_file.status IS '扫描状态';
COMMENT ON COLUMN task_file.error_message IS '错误信息';
COMMENT ON COLUMN task_file.issue_count IS '问题数量';
COMMENT ON COLUMN task_file.start_time IS '开始时间';
COMMENT ON COLUMN task_file.end_time IS '结束时间';

-- 扫描结果汇总字段中文名称及说明
COMMENT ON COLUMN scan_result.id IS '结果ID：主键';
COMMENT ON COLUMN scan_result.task_id IS '任务ID';
COMMENT ON COLUMN scan_result.scanned_files IS '已扫描文件数';
COMMENT ON COLUMN scan_result.success_files IS '成功文件数';
COMMENT ON COLUMN scan_result.failed_files IS '失败文件数';
COMMENT ON COLUMN scan_result.issue_count IS '问题总数';
COMMENT ON COLUMN scan_result.high_count IS '高风险问题数';
COMMENT ON COLUMN scan_result.medium_count IS '中风险问题数';
COMMENT ON COLUMN scan_result.low_count IS '低风险问题数';
COMMENT ON COLUMN scan_result.info_count IS '提示级问题数';
COMMENT ON COLUMN scan_result.create_time IS '创建时间';
COMMENT ON COLUMN scan_result.operator_user_id IS '操作用户ID';
COMMENT ON COLUMN scan_result.operator_user_name IS '操作用户名称';

-- 扫描问题字段中文名称及说明
COMMENT ON COLUMN scan_issue.id IS '问题ID：主键';
COMMENT ON COLUMN scan_issue.result_id IS '扫描结果ID';
COMMENT ON COLUMN scan_issue.task_id IS '任务ID';
COMMENT ON COLUMN scan_issue.repository_id IS '仓库ID';
COMMENT ON COLUMN scan_issue.rule_id IS '规则ID';
COMMENT ON COLUMN scan_issue.title IS '问题标题';
COMMENT ON COLUMN scan_issue.risk_level IS '风险等级';
COMMENT ON COLUMN scan_issue.rule_type IS '规则类型';
COMMENT ON COLUMN scan_issue.file_path IS '文件路径';
COMMENT ON COLUMN scan_issue.start_line IS '起始行号';
COMMENT ON COLUMN scan_issue.end_line IS '结束行号';
COMMENT ON COLUMN scan_issue.matched_content IS '匹配内容';
COMMENT ON COLUMN scan_issue.context_content IS '上下文内容';
COMMENT ON COLUMN scan_issue.issue_description IS '问题说明';
COMMENT ON COLUMN scan_issue.suggestion IS '整改建议';
COMMENT ON COLUMN scan_issue.status IS '处理状态';
COMMENT ON COLUMN scan_issue.handle_comment IS '处理意见';
COMMENT ON COLUMN scan_issue.handler IS '处理人';
COMMENT ON COLUMN scan_issue.handle_time IS '处理时间';
COMMENT ON COLUMN scan_issue.create_time IS '创建时间';
COMMENT ON COLUMN scan_issue.operator_user_id IS '操作用户ID';
COMMENT ON COLUMN scan_issue.operator_user_name IS '操作用户名称';

UPDATE scan_rule SET operator_user_id=1,operator_user_name='admin' WHERE operator_user_id IS NULL;
UPDATE code_repository SET operator_user_id=1,operator_user_name='admin' WHERE operator_user_id IS NULL;
UPDATE scan_task SET operator_user_id=1,operator_user_name='admin' WHERE operator_user_id IS NULL;
UPDATE scan_result SET operator_user_id=1,operator_user_name='admin' WHERE operator_user_id IS NULL;
UPDATE scan_issue SET operator_user_id=1,operator_user_name='admin' WHERE operator_user_id IS NULL;
