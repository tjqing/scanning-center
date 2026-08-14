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
 role_code VARCHAR(20) NOT NULL, description VARCHAR(500),
 enabled BOOLEAN DEFAULT TRUE, deleted BOOLEAN DEFAULT FALSE,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_query ON system_user(deleted,enabled,role_code);
CREATE TABLE IF NOT EXISTS system_setting (
 setting_key VARCHAR(100) PRIMARY KEY, setting_value VARCHAR(1000) NOT NULL, description VARCHAR(500),
 operator_user_id BIGINT, operator_user_name VARCHAR(128),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO system_setting(setting_key,setting_value,description)
SELECT 'AI_TOKEN_RETRY_COUNT','3','大模型Token调用失败后的重试次数'
WHERE NOT EXISTS (SELECT 1 FROM system_setting WHERE setting_key='AI_TOKEN_RETRY_COUNT');
INSERT INTO system_setting(setting_key,setting_value,description)
SELECT 'AI_SCAN_TASK_CONCURRENCY','1','服务器可同时运行的AI类型扫描任务数量'
WHERE NOT EXISTS (SELECT 1 FROM system_setting WHERE setting_key='AI_SCAN_TASK_CONCURRENCY');
INSERT INTO system_setting(setting_key,setting_value,description)
SELECT 'AI_SCHEDULE_WINDOW_START','20:00','AI任务允许定时发起的开始时刻（含跨天）'
WHERE NOT EXISTS (SELECT 1 FROM system_setting WHERE setting_key='AI_SCHEDULE_WINDOW_START');
INSERT INTO system_setting(setting_key,setting_value,description)
SELECT 'AI_SCHEDULE_WINDOW_END','08:00','AI任务允许定时发起的结束时刻（含跨天）'
WHERE NOT EXISTS (SELECT 1 FROM system_setting WHERE setting_key='AI_SCHEDULE_WINDOW_END');
CREATE TABLE IF NOT EXISTS code_repository (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_name VARCHAR(128) NOT NULL, source_type VARCHAR(20) NOT NULL,
 description VARCHAR(1000), repository_url VARCHAR(1000), default_branch VARCHAR(128),
 storage_key VARCHAR(500), original_file_name VARCHAR(255), enabled BOOLEAN DEFAULT TRUE, deleted BOOLEAN DEFAULT FALSE,
 last_scan_time TIMESTAMP, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
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
COMMENT ON COLUMN code_repository.default_branch IS '默认分支';
COMMENT ON COLUMN code_repository.storage_key IS '存储标识';
COMMENT ON COLUMN code_repository.original_file_name IS '原始文件名';
COMMENT ON COLUMN code_repository.enabled IS '是否启用';
COMMENT ON COLUMN code_repository.deleted IS '是否删除';
COMMENT ON COLUMN code_repository.last_scan_time IS '最后扫描时间';
COMMENT ON COLUMN code_repository.create_time IS '创建时间';
COMMENT ON COLUMN code_repository.update_time IS '更新时间';
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

-- 详细方案增量结构：规则权限、应用、大模型配置、任务快照、扫描清单及断点续扫
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) DEFAULT 'PRIVATE';
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS owner_user_id BIGINT DEFAULT 1;
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS owner_user_name VARCHAR(128) DEFAULT 'admin';
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS check_rule_content CLOB;
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS result_update_content CLOB;
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS shared_by_user_id BIGINT;
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS shared_by_user_name VARCHAR(128);
ALTER TABLE scan_rule ADD COLUMN IF NOT EXISTS shared_time TIMESTAMP;

ALTER TABLE system_user ADD COLUMN IF NOT EXISTS external_user_id VARCHAR(128);
ALTER TABLE system_user ADD COLUMN IF NOT EXISTS application VARCHAR(128);
ALTER TABLE system_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(256);
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_user_username ON system_user(username);

CREATE TABLE IF NOT EXISTS repository_catalog (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 repository_name VARCHAR(255) NOT NULL,
 repository_url VARCHAR(1000) NOT NULL,
 application VARCHAR(20) NOT NULL,
 version_no VARCHAR(20),
 auth_type VARCHAR(20) DEFAULT 'HTTPS_TOKEN',
 git_username VARCHAR(128),
 encrypted_secret CLOB,
 enabled BOOLEAN DEFAULT TRUE,
 deleted BOOLEAN DEFAULT FALSE,
 operator_user_id BIGINT,
 operator_user_name VARCHAR(128),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_repository_catalog_name UNIQUE(repository_name)
);
CREATE INDEX IF NOT EXISTS idx_repository_catalog_application ON repository_catalog(application,enabled,deleted);
ALTER TABLE repository_catalog ADD COLUMN IF NOT EXISTS auth_type VARCHAR(20) DEFAULT 'HTTPS_TOKEN';
ALTER TABLE repository_catalog ADD COLUMN IF NOT EXISTS git_username VARCHAR(128);
ALTER TABLE repository_catalog ADD COLUMN IF NOT EXISTS encrypted_secret CLOB;
ALTER TABLE repository_catalog ADD COLUMN IF NOT EXISTS version_no VARCHAR(20);

CREATE TABLE IF NOT EXISTS user_repository_relation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 repository_id BIGINT NOT NULL,
 create_user_id BIGINT,
 create_user_name VARCHAR(128),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_user_repository_relation UNIQUE(user_id,repository_id)
);
CREATE INDEX IF NOT EXISTS idx_user_repository_user ON user_repository_relation(user_id);

ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS repository_code VARCHAR(64);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS application VARCHAR(128);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS scan_source_type VARCHAR(20) DEFAULT 'CODE';
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS repository_catalog_id BIGINT;
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS git_projects_json CLOB;
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS md_document_type VARCHAR(30);
ALTER TABLE code_repository ADD COLUMN IF NOT EXISTS version_no VARCHAR(20);
ALTER TABLE code_repository DROP COLUMN IF EXISTS design_document_path;
UPDATE code_repository SET scan_source_type=CASE WHEN source_type IN ('DATABASE','HTTP') THEN 'MD' ELSE 'CODE' END;
UPDATE code_repository SET source_type='HTTP' WHERE source_type='DATABASE';
UPDATE scan_rule SET rule_type='AI' WHERE rule_type='MD';

ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS owner_user_id BIGINT DEFAULT 1;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS owner_user_name VARCHAR(128) DEFAULT 'admin';
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS current_snapshot_id BIGINT;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS current_run_id BIGINT;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS task_type VARCHAR(20);
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS manifest_status VARCHAR(20);
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS manifest_file_count INT DEFAULT 0;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE scan_task ADD COLUMN IF NOT EXISTS schedule_time TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_task_schedule ON scan_task(schedule_type, schedule_time);
UPDATE scan_task SET task_type='AI' WHERE task_type='MD';

CREATE TABLE IF NOT EXISTS model_prompt_template (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 prompt_type VARCHAR(30) NOT NULL,
 version_no INT NOT NULL,
 prompt_content CLOB NOT NULL,
 json_schema CLOB,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 operator_user_id BIGINT,
 operator_user_name VARCHAR(128),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_model_prompt_version UNIQUE(prompt_type, version_no)
);

CREATE TABLE IF NOT EXISTS user_model_credential (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 credential_name VARCHAR(128) NOT NULL,
 ucid VARCHAR(255) NOT NULL,
 token_ciphertext VARCHAR(2000) NOT NULL,
 token_masked VARCHAR(64),
 enabled BOOLEAN DEFAULT TRUE,
 runtime_status VARCHAR(20) DEFAULT 'AVAILABLE',
 lease_id VARCHAR(64),
 leased_run_id BIGINT,
 lease_expire_time TIMESTAMP,
 cooldown_until TIMESTAMP,
 last_used_time TIMESTAMP,
 last_test_time TIMESTAMP,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_user_model_credential UNIQUE(user_id, ucid)
);

CREATE TABLE IF NOT EXISTS task_snapshot (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 snapshot_version INT NOT NULL,
 task_name_snapshot VARCHAR(128) NOT NULL,
 description_snapshot VARCHAR(1000),
 task_type VARCHAR(20) NOT NULL,
 scan_source_type VARCHAR(20) NOT NULL DEFAULT 'CODE',
 repository_id BIGINT NOT NULL,
 source_snapshot CLOB,
 application VARCHAR(128),
 version_no VARCHAR(20),
 scan_paths CLOB,
 file_types VARCHAR(1000),
 exclude_paths CLOB,
 prompt_snapshot CLOB,
 snapshot_hash VARCHAR(64),
 server_root_path VARCHAR(1000),
 created_by_user_id BIGINT,
 created_by_user_name VARCHAR(128),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_task_snapshot_version UNIQUE(task_id, snapshot_version)
);
ALTER TABLE task_snapshot ADD COLUMN IF NOT EXISTS scan_source_type VARCHAR(20) DEFAULT 'CODE';
UPDATE task_snapshot SET scan_source_type='MD',task_type='AI' WHERE task_type='MD';

CREATE TABLE IF NOT EXISTS task_snapshot_rule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_snapshot_id BIGINT NOT NULL,
 rule_id BIGINT NOT NULL,
 rule_type VARCHAR(20) NOT NULL,
 rule_order INT NOT NULL,
 rule_snapshot CLOB NOT NULL,
 CONSTRAINT uk_task_snapshot_rule UNIQUE(task_snapshot_id, rule_id)
);
UPDATE task_snapshot_rule SET rule_type='AI' WHERE rule_type='MD';

CREATE TABLE IF NOT EXISTS task_scan_manifest (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_snapshot_id BIGINT NOT NULL,
 manifest_version INT NOT NULL,
 status VARCHAR(20) NOT NULL,
 file_count INT DEFAULT 0,
 excluded_count INT DEFAULT 0,
 total_size BIGINT DEFAULT 0,
 exclude_summary CLOB,
 manifest_hash VARCHAR(64),
 error_message VARCHAR(2000),
 start_time TIMESTAMP,
 finish_time TIMESTAMP,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_task_manifest_version UNIQUE(task_snapshot_id, manifest_version)
);

CREATE TABLE IF NOT EXISTS task_scan_manifest_file (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 manifest_id BIGINT NOT NULL,
 relative_path VARCHAR(1000) NOT NULL,
 file_type VARCHAR(32),
 file_size BIGINT DEFAULT 0,
 last_modified_time TIMESTAMP,
 content_hash VARCHAR(64),
 applicable_rule_count INT DEFAULT 0,
 CONSTRAINT uk_task_manifest_file UNIQUE(manifest_id, relative_path)
);
CREATE INDEX IF NOT EXISTS idx_manifest_file_query ON task_scan_manifest_file(manifest_id,file_type);

CREATE TABLE IF NOT EXISTS scan_task_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 run_no VARCHAR(64) NOT NULL,
 task_id BIGINT NOT NULL,
 task_snapshot_id BIGINT NOT NULL,
 manifest_id BIGINT NOT NULL,
 status VARCHAR(30) NOT NULL,
 stop_requested BOOLEAN DEFAULT FALSE,
 resume_count INT DEFAULT 0,
 total_units INT DEFAULT 0,
 completed_units INT DEFAULT 0,
 success_units INT DEFAULT 0,
 failed_units INT DEFAULT 0,
 issue_count INT DEFAULT 0,
 started_by_user_id BIGINT,
 started_by_user_name VARCHAR(128),
 error_message VARCHAR(2000),
 queue_time TIMESTAMP,
 start_time TIMESTAMP,
 end_time TIMESTAMP,
 last_checkpoint_time TIMESTAMP,
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_scan_task_run_no UNIQUE(run_no)
);
CREATE INDEX IF NOT EXISTS idx_task_run_status ON scan_task_run(status,update_time);

CREATE TABLE IF NOT EXISTS task_execution_unit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 run_id BIGINT NOT NULL,
 manifest_file_id BIGINT NOT NULL,
 task_snapshot_rule_id BIGINT NOT NULL,
 segment_no INT DEFAULT 0,
 stage VARCHAR(30) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 checkpoint_data CLOB,
 preliminary_result CLOB,
 retry_count INT DEFAULT 0,
 lease_id VARCHAR(64),
 lease_expire_time TIMESTAMP,
 result_commit_key VARCHAR(128) NOT NULL,
 error_message VARCHAR(2000),
 start_time TIMESTAMP,
 finish_time TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_task_execution_unit UNIQUE(run_id,manifest_file_id,task_snapshot_rule_id,segment_no,stage),
 CONSTRAINT uk_task_execution_commit UNIQUE(result_commit_key)
);
CREATE INDEX IF NOT EXISTS idx_execution_unit_pick ON task_execution_unit(run_id,status,id);

ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE scan_result DROP CONSTRAINT IF EXISTS uk_scan_result_task;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_result_run ON scan_result(run_id);
ALTER TABLE scan_issue ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE scan_issue ADD COLUMN IF NOT EXISTS execution_unit_id BIGINT;
ALTER TABLE scan_issue ADD COLUMN IF NOT EXISTS result_commit_key VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_issue_commit ON scan_issue(result_commit_key);

-- 保存每个执行单元的完整 AI 调用报文（JSON）
ALTER TABLE task_execution_unit ADD COLUMN IF NOT EXISTS ai_response_json CLOB;
-- AI 请求报文字符数（一次执行单元内所有 AI 调用 requestChars 之和）
ALTER TABLE task_execution_unit ADD COLUMN IF NOT EXISTS request_chars INT DEFAULT 0;
-- AI 请求 token 数（一次执行单元内所有 AI 调用 prompt_tokens 之和）
ALTER TABLE task_execution_unit ADD COLUMN IF NOT EXISTS prompt_tokens INT DEFAULT 0;

INSERT INTO model_prompt_template(prompt_type,version_no,prompt_content,status,operator_user_id,operator_user_name)
SELECT 'AI_CHECK',1,'你是代码扫描助手。请根据检查规则检查给定文件内容，只返回符合约定结构的JSON。','ACTIVE',1,'admin'
WHERE NOT EXISTS(SELECT 1 FROM model_prompt_template WHERE prompt_type='AI_CHECK');
INSERT INTO model_prompt_template(prompt_type,version_no,prompt_content,status,operator_user_id,operator_user_name)
SELECT 'AI_RESULT_UPDATE',1,'你是扫描结果整理助手。请根据结果更新规则整理初步问题，只返回符合约定结构的JSON。','ACTIVE',1,'admin'
WHERE NOT EXISTS(SELECT 1 FROM model_prompt_template WHERE prompt_type='AI_RESULT_UPDATE');
INSERT INTO model_prompt_template(prompt_type,version_no,prompt_content,status,operator_user_id,operator_user_name)
SELECT 'MD_CHECK',1,'你是设计文档扫描助手。请根据检查规则检查应用版本文档，只返回符合约定结构的JSON。','ACTIVE',1,'admin'
WHERE NOT EXISTS(SELECT 1 FROM model_prompt_template WHERE prompt_type='MD_CHECK');
