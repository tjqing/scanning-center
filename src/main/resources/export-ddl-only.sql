-- MySQL 8 建表脚本（由本地 H2 库结构导出，含中文注释；缺省注释已按业务词典补齐）
-- 使用前请先：CREATE DATABASE scanning_center DEFAULT CHARACTER SET utf8mb4;
USE scanning_center;

CREATE TABLE IF NOT EXISTS `code_repository` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '仓库ID：主键',
  `repository_name` VARCHAR(128) NOT NULL COMMENT '仓库名称',
  `source_type` VARCHAR(20) NOT NULL COMMENT '数据源类型',
  `description` VARCHAR(1000) DEFAULT NULL COMMENT '仓库描述',
  `repository_url` VARCHAR(1000) DEFAULT NULL COMMENT '仓库地址',
  `default_branch` VARCHAR(128) DEFAULT NULL COMMENT '默认分支',
  `storage_key` VARCHAR(500) DEFAULT NULL COMMENT '存储标识',
  `original_file_name` VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
  `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除',
  `last_scan_time` TIMESTAMP DEFAULT NULL COMMENT '最后扫描时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `repository_code` VARCHAR(64) DEFAULT NULL COMMENT '仓库编码',
  `application` VARCHAR(128) DEFAULT NULL COMMENT '所属应用',
  `scan_source_type` VARCHAR(20) DEFAULT 'CODE' COMMENT '扫描源类型：CODE/MD',
  `repository_catalog_id` BIGINT DEFAULT NULL COMMENT '关联代码库目录ID',
  `version_no` VARCHAR(20) DEFAULT NULL COMMENT '应用版本号',
  `git_projects_json` LONGTEXT DEFAULT NULL COMMENT 'Git项目清单JSON',
  `md_document_type` VARCHAR(30) DEFAULT NULL COMMENT 'MD文档类型',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码仓库：保存待扫描代码、压缩包或数据库文档源配置';

CREATE TABLE IF NOT EXISTS `model_prompt_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '模板ID：主键',
  `prompt_type` VARCHAR(30) NOT NULL COMMENT '提示词类型：AI_CHECK/AI_RESULT_UPDATE/MD_CHECK',
  `version_no` INT NOT NULL COMMENT '版本号',
  `prompt_content` LONGTEXT NOT NULL COMMENT '提示词内容',
  `json_schema` LONGTEXT DEFAULT NULL COMMENT '输出JSON结构约束',
  `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_prompt_version` (`prompt_type`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型提示词模板：管理员维护的内置提示词及版本';

CREATE TABLE IF NOT EXISTS `repository_catalog` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '目录ID：主键',
  `repository_name` VARCHAR(255) NOT NULL COMMENT '代码库名称',
  `repository_url` VARCHAR(1000) NOT NULL COMMENT '代码库地址',
  `application` VARCHAR(20) NOT NULL COMMENT '所属应用',
  `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `auth_type` VARCHAR(20) DEFAULT 'HTTPS_TOKEN' COMMENT '认证类型：HTTPS_TOKEN/SSH_KEY等',
  `git_username` VARCHAR(128) DEFAULT NULL COMMENT 'Git访问用户名',
  `encrypted_secret` LONGTEXT DEFAULT NULL COMMENT '加密后的访问密钥/私钥',
  `version_no` VARCHAR(20) DEFAULT NULL COMMENT '版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_repository_catalog_name_index_b` (`repository_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码库目录：管理员维护的可用 Git 代码库清单';

CREATE TABLE IF NOT EXISTS `scan_issue` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '问题ID：主键',
  `result_id` BIGINT NOT NULL COMMENT '扫描结果ID',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `repository_id` BIGINT NOT NULL COMMENT '仓库ID',
  `rule_id` BIGINT NOT NULL COMMENT '规则ID',
  `title` VARCHAR(500) NOT NULL COMMENT '问题标题',
  `risk_level` VARCHAR(20) NOT NULL COMMENT '风险等级',
  `rule_type` VARCHAR(20) NOT NULL COMMENT '规则类型',
  `file_path` VARCHAR(1000) DEFAULT NULL COMMENT '文件路径',
  `start_line` INT DEFAULT NULL COMMENT '起始行号',
  `end_line` INT DEFAULT NULL COMMENT '结束行号',
  `matched_content` LONGTEXT DEFAULT NULL COMMENT '匹配内容',
  `context_content` LONGTEXT DEFAULT NULL COMMENT '上下文内容',
  `issue_description` VARCHAR(2000) DEFAULT NULL COMMENT '问题说明',
  `suggestion` VARCHAR(2000) DEFAULT NULL COMMENT '整改建议',
  `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '处理状态',
  `handle_comment` VARCHAR(2000) DEFAULT NULL COMMENT '处理意见',
  `handler` VARCHAR(128) DEFAULT NULL COMMENT '处理人',
  `handle_time` TIMESTAMP DEFAULT NULL COMMENT '处理时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `run_id` BIGINT DEFAULT NULL COMMENT '关联运行ID',
  `execution_unit_id` BIGINT DEFAULT NULL COMMENT '关联执行单元ID',
  `result_commit_key` VARCHAR(128) DEFAULT NULL COMMENT '结果幂等提交键',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_issue_commit` (`result_commit_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描问题：保存扫描发现的具体问题及处理情况';

CREATE TABLE IF NOT EXISTS `scan_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '结果ID：主键',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `scanned_files` INT DEFAULT 0 COMMENT '已扫描文件数',
  `success_files` INT DEFAULT 0 COMMENT '成功文件数',
  `failed_files` INT DEFAULT 0 COMMENT '失败文件数',
  `issue_count` INT DEFAULT 0 COMMENT '问题总数',
  `high_count` INT DEFAULT 0 COMMENT '高风险问题数',
  `medium_count` INT DEFAULT 0 COMMENT '中风险问题数',
  `low_count` INT DEFAULT 0 COMMENT '低风险问题数',
  `info_count` INT DEFAULT 0 COMMENT '提示级问题数',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `run_id` BIGINT DEFAULT NULL COMMENT '关联运行ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_result_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描结果汇总：保存扫描任务的统计结果';

CREATE TABLE IF NOT EXISTS `scan_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID：主键',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则编码：规则唯一标识',
  `rule_name` VARCHAR(128) NOT NULL COMMENT '规则名称',
  `description` VARCHAR(1000) DEFAULT NULL COMMENT '规则描述',
  `rule_type` VARCHAR(20) NOT NULL COMMENT '规则类型',
  `target_type` VARCHAR(20) NOT NULL COMMENT '扫描目标类型',
  `risk_level` VARCHAR(20) NOT NULL COMMENT '风险等级',
  `match_type` VARCHAR(20) DEFAULT NULL COMMENT '匹配方式',
  `match_content` LONGTEXT DEFAULT NULL COMMENT '匹配内容',
  `case_sensitive` TINYINT(1) DEFAULT 0 COMMENT '是否区分大小写',
  `file_types` VARCHAR(500) DEFAULT NULL COMMENT '适用文件类型',
  `exclude_patterns` VARCHAR(1000) DEFAULT NULL COMMENT '排除模式',
  `issue_description` VARCHAR(1000) DEFAULT NULL COMMENT '问题说明',
  `suggestion` VARCHAR(1000) DEFAULT NULL COMMENT '整改建议',
  `prompt_content` LONGTEXT DEFAULT NULL COMMENT '提示词内容',
  `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `visibility` VARCHAR(20) DEFAULT 'PRIVATE' COMMENT '可见性：PRIVATE/SHARED',
  `owner_user_id` BIGINT DEFAULT 1 COMMENT '规则所有者用户ID',
  `owner_user_name` VARCHAR(128) DEFAULT 'admin' COMMENT '规则所有者用户名',
  `check_rule_content` LONGTEXT DEFAULT NULL COMMENT 'AI检查规则内容',
  `result_update_content` LONGTEXT DEFAULT NULL COMMENT 'AI结果更新内容',
  `shared_by_user_id` BIGINT DEFAULT NULL COMMENT '共享操作人用户ID',
  `shared_by_user_name` VARCHAR(128) DEFAULT NULL COMMENT '共享操作人用户名',
  `shared_time` TIMESTAMP DEFAULT NULL COMMENT '共享时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_rule_code` (`rule_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描规则：定义代码或文档扫描时使用的检查规则';

CREATE TABLE IF NOT EXISTS `scan_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID：主键',
  `task_no` VARCHAR(64) NOT NULL COMMENT '任务编号：任务唯一标识',
  `task_name` VARCHAR(128) NOT NULL COMMENT '任务名称',
  `description` VARCHAR(1000) DEFAULT NULL COMMENT '任务描述',
  `repository_id` BIGINT NOT NULL COMMENT '仓库ID',
  `scope_json` LONGTEXT DEFAULT NULL COMMENT '扫描范围配置JSON',
  `status` VARCHAR(30) NOT NULL COMMENT '任务状态',
  `total_files` INT DEFAULT 0 COMMENT '文件总数',
  `completed_files` INT DEFAULT 0 COMMENT '已完成文件数',
  `success_files` INT DEFAULT 0 COMMENT '成功文件数',
  `failed_files` INT DEFAULT 0 COMMENT '失败文件数',
  `issue_count` INT DEFAULT 0 COMMENT '问题数量',
  `cancel_requested` TINYINT(1) DEFAULT 0 COMMENT '是否请求取消',
  `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
  `start_time` TIMESTAMP DEFAULT NULL COMMENT '开始时间',
  `end_time` TIMESTAMP DEFAULT NULL COMMENT '结束时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `owner_user_id` BIGINT DEFAULT 1 COMMENT '任务所有者用户ID',
  `owner_user_name` VARCHAR(128) DEFAULT 'admin' COMMENT '任务所有者用户名',
  `current_snapshot_id` BIGINT DEFAULT NULL COMMENT '当前快照ID',
  `current_run_id` BIGINT DEFAULT NULL COMMENT '当前运行ID',
  `task_type` VARCHAR(20) DEFAULT NULL COMMENT '任务类型：NORMAL/AI',
  `manifest_status` VARCHAR(20) DEFAULT NULL COMMENT '清单状态',
  `manifest_file_count` INT DEFAULT 0 COMMENT '清单文件数',
  `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否逻辑删除',
  `schedule_type` VARCHAR(20) DEFAULT 'NONE' COMMENT '定时类型：NONE=不定时/ONCE=一次性定时',
  `schedule_time` TIMESTAMP DEFAULT NULL COMMENT '定时发起时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_task_no` (`task_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描任务：记录一次扫描任务的配置、进度和状态';

CREATE TABLE IF NOT EXISTS `scan_task_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '运行ID：主键',
  `run_no` VARCHAR(64) NOT NULL COMMENT '运行编号：唯一标识',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `task_snapshot_id` BIGINT NOT NULL COMMENT '任务快照ID',
  `manifest_id` BIGINT NOT NULL COMMENT '扫描清单ID',
  `status` VARCHAR(30) NOT NULL COMMENT '运行状态',
  `stop_requested` TINYINT(1) DEFAULT 0 COMMENT '是否请求停止',
  `resume_count` INT DEFAULT 0 COMMENT '继续/续扫次数',
  `total_units` INT DEFAULT 0 COMMENT '执行单元总数',
  `completed_units` INT DEFAULT 0 COMMENT '已完成执行单元数',
  `success_units` INT DEFAULT 0 COMMENT '成功执行单元数',
  `failed_units` INT DEFAULT 0 COMMENT '失败执行单元数',
  `issue_count` INT DEFAULT 0 COMMENT '本运行问题数',
  `started_by_user_id` BIGINT DEFAULT NULL COMMENT '启动人用户ID',
  `started_by_user_name` VARCHAR(128) DEFAULT NULL COMMENT '启动人用户名',
  `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
  `queue_time` TIMESTAMP DEFAULT NULL COMMENT '入队时间',
  `start_time` TIMESTAMP DEFAULT NULL COMMENT '开始执行时间',
  `end_time` TIMESTAMP DEFAULT NULL COMMENT '结束时间',
  `last_checkpoint_time` TIMESTAMP DEFAULT NULL COMMENT '最后检查点时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_task_run_no_index_b` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务运行：一次扫描任务的运行实例与进度';

CREATE TABLE IF NOT EXISTS `system_setting` (
  `setting_key` VARCHAR(100) NOT NULL COMMENT '配置键：主键',
  `setting_value` VARCHAR(1000) NOT NULL COMMENT '配置值',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '配置说明',
  `operator_user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `operator_user_name` VARCHAR(128) DEFAULT NULL COMMENT '操作用户名称',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统设置：保存全局配置项（如 AI 重试次数、并发与定时窗口）';

CREATE TABLE IF NOT EXISTS `system_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID：主键',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `display_name` VARCHAR(128) NOT NULL COMMENT '显示名称',
  `role_code` VARCHAR(20) NOT NULL COMMENT '角色编码',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '用户描述',
  `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `external_user_id` VARCHAR(128) DEFAULT NULL COMMENT '外部用户ID：测试案例平台用户唯一标识',
  `application` VARCHAR(128) DEFAULT NULL COMMENT '所属应用：管理员为ALL',
  `password_hash` VARCHAR(256) DEFAULT NULL COMMENT '密码摘要：Base64(SHA-256(pepper:password))',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户：保存系统登录用户及其基本信息';

CREATE TABLE IF NOT EXISTS `task_execution_unit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '执行单元ID：主键',
  `run_id` BIGINT NOT NULL COMMENT '运行ID',
  `manifest_file_id` BIGINT NOT NULL COMMENT '清单文件ID',
  `task_snapshot_rule_id` BIGINT NOT NULL COMMENT '快照规则ID',
  `segment_no` INT DEFAULT 0 COMMENT '分段号',
  `stage` VARCHAR(30) NOT NULL COMMENT '执行阶段：NORMAL_MATCH/AI_CHECK/MD_CHECK等',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCESS/FAILED',
  `checkpoint_data` LONGTEXT DEFAULT NULL COMMENT '检查点数据',
  `preliminary_result` LONGTEXT DEFAULT NULL COMMENT 'AI初步结果',
  `retry_count` INT DEFAULT 0 COMMENT '重试次数',
  `lease_id` VARCHAR(64) DEFAULT NULL COMMENT '租约ID（断点续扫）',
  `lease_expire_time` TIMESTAMP DEFAULT NULL COMMENT '租约过期时间',
  `result_commit_key` VARCHAR(128) NOT NULL COMMENT '结果幂等提交键',
  `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
  `start_time` TIMESTAMP DEFAULT NULL COMMENT '开始时间',
  `finish_time` TIMESTAMP DEFAULT NULL COMMENT '结束时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `ai_response_json` LONGTEXT DEFAULT NULL COMMENT 'AI调用完整响应JSON',
  `request_chars` INT DEFAULT 0 COMMENT 'AI请求报文字符数',
  `prompt_tokens` INT DEFAULT 0 COMMENT 'AI请求prompt token数',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_execution_commit` (`result_commit_key`),
  UNIQUE KEY `uk_task_execution_unit` (`run_id`, `manifest_file_id`, `task_snapshot_rule_id`, `segment_no`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行单元：运行中单文件单规则的最小执行与续扫单元';

CREATE TABLE IF NOT EXISTS `task_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件记录ID：主键',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `relative_path` VARCHAR(1000) NOT NULL COMMENT '文件相对路径',
  `status` VARCHAR(20) NOT NULL COMMENT '扫描状态',
  `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
  `issue_count` INT DEFAULT 0 COMMENT '问题数量',
  `start_time` TIMESTAMP DEFAULT NULL COMMENT '开始时间',
  `end_time` TIMESTAMP DEFAULT NULL COMMENT '结束时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务文件：记录扫描任务中单个文件的执行情况';

CREATE TABLE IF NOT EXISTS `task_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联ID：主键',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `rule_id` BIGINT NOT NULL COMMENT '规则ID',
  `rule_snapshot` LONGTEXT DEFAULT NULL COMMENT '规则快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_rule_index_b` (`task_id`, `rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务规则关联：保存扫描任务使用的规则及规则快照';

CREATE TABLE IF NOT EXISTS `task_scan_manifest` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '清单ID：主键',
  `task_snapshot_id` BIGINT NOT NULL COMMENT '任务快照ID',
  `manifest_version` INT NOT NULL COMMENT '清单版本号',
  `status` VARCHAR(20) NOT NULL COMMENT '清单状态：BUILDING/READY/FAILED/INVALID',
  `file_count` INT DEFAULT 0 COMMENT '纳入文件数',
  `excluded_count` INT DEFAULT 0 COMMENT '排除文件数',
  `total_size` BIGINT DEFAULT 0 COMMENT '纳入文件总大小（字节）',
  `exclude_summary` LONGTEXT DEFAULT NULL COMMENT '排除摘要JSON',
  `manifest_hash` VARCHAR(64) DEFAULT NULL COMMENT '清单整体摘要',
  `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
  `start_time` TIMESTAMP DEFAULT NULL COMMENT '开始构建时间',
  `finish_time` TIMESTAMP DEFAULT NULL COMMENT '构建完成时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_manifest_version` (`task_snapshot_id`, `manifest_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描清单：某次快照下纳入扫描的文件清单';

CREATE TABLE IF NOT EXISTS `task_scan_manifest_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '清单文件ID：主键',
  `manifest_id` BIGINT NOT NULL COMMENT '清单ID',
  `relative_path` VARCHAR(200) NOT NULL COMMENT '文件相对路径',
  `file_type` VARCHAR(32) DEFAULT NULL COMMENT '文件类型',
  `file_size` BIGINT DEFAULT 0 COMMENT '文件大小（字节）',
  `last_modified_time` TIMESTAMP DEFAULT NULL COMMENT '文件最后修改时间',
  `content_hash` VARCHAR(64) DEFAULT NULL COMMENT '文件内容摘要SHA-256',
  `applicable_rule_count` INT DEFAULT 0 COMMENT '适用规则数',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_manifest_file` (`manifest_id`, `relative_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描清单文件：清单中的单个文件元数据';

CREATE TABLE IF NOT EXISTS `task_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '快照ID：主键',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `snapshot_version` INT NOT NULL COMMENT '快照版本号',
  `task_name_snapshot` VARCHAR(128) NOT NULL COMMENT '任务名称快照',
  `description_snapshot` VARCHAR(1000) DEFAULT NULL COMMENT '任务描述快照',
  `task_type` VARCHAR(20) NOT NULL COMMENT '任务类型：NORMAL/AI',
  `scan_source_type` VARCHAR(20) NOT NULL DEFAULT 'CODE' COMMENT '扫描源类型：CODE/MD',
  `repository_id` BIGINT NOT NULL COMMENT '扫描源ID',
  `source_snapshot` LONGTEXT DEFAULT NULL COMMENT '扫描源配置快照JSON',
  `application` VARCHAR(128) DEFAULT NULL COMMENT '应用',
  `version_no` VARCHAR(20) DEFAULT NULL COMMENT '版本号',
  `scan_paths` LONGTEXT DEFAULT NULL COMMENT '扫描路径快照JSON',
  `file_types` VARCHAR(1000) DEFAULT NULL COMMENT '扫描文件类型',
  `exclude_paths` LONGTEXT DEFAULT NULL COMMENT '排除路径快照JSON',
  `prompt_snapshot` LONGTEXT DEFAULT NULL COMMENT '提示词快照JSON',
  `snapshot_hash` VARCHAR(64) DEFAULT NULL COMMENT '快照摘要SHA-256',
  `server_root_path` VARCHAR(1000) DEFAULT NULL COMMENT '服务端工作目录路径',
  `created_by_user_id` BIGINT DEFAULT NULL COMMENT '创建人用户ID',
  `created_by_user_name` VARCHAR(128) DEFAULT NULL COMMENT '创建人用户名',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_snapshot_version` (`task_id`, `snapshot_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务快照：任务启动时固化的配置与提示词快照';

CREATE TABLE IF NOT EXISTS `task_snapshot_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '快照规则ID：主键',
  `task_snapshot_id` BIGINT NOT NULL COMMENT '任务快照ID',
  `rule_id` BIGINT NOT NULL COMMENT '规则ID',
  `rule_type` VARCHAR(20) NOT NULL COMMENT '规则类型：NORMAL/AI',
  `rule_order` INT NOT NULL COMMENT '规则执行顺序',
  `rule_snapshot` LONGTEXT NOT NULL COMMENT '规则完整快照JSON',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_snapshot_rule` (`task_snapshot_id`, `rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务快照规则：快照中绑定的规则及规则内容快照';

CREATE TABLE IF NOT EXISTS `user_model_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭证ID：主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `credential_name` VARCHAR(128) NOT NULL COMMENT '凭证名称',
  `ucid` VARCHAR(255) NOT NULL COMMENT '大模型调用UCID',
  `token_ciphertext` VARCHAR(2000) NOT NULL COMMENT 'Token密文',
  `token_masked` VARCHAR(64) DEFAULT NULL COMMENT 'Token脱敏展示值',
  `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `runtime_status` VARCHAR(20) DEFAULT 'AVAILABLE' COMMENT '运行状态：AVAILABLE/BUSY/COOLDOWN',
  `lease_id` VARCHAR(64) DEFAULT NULL COMMENT '租约ID',
  `leased_run_id` BIGINT DEFAULT NULL COMMENT '租约关联运行ID',
  `lease_expire_time` TIMESTAMP DEFAULT NULL COMMENT '租约过期时间',
  `cooldown_until` TIMESTAMP DEFAULT NULL COMMENT '冷却截止时间',
  `last_used_time` TIMESTAMP DEFAULT NULL COMMENT '最近使用时间',
  `last_test_time` TIMESTAMP DEFAULT NULL COMMENT '最近测试时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_model_credential_index_f` (`user_id`, `ucid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户模型凭证：用户本人的 UCID/Token 凭证池';

CREATE TABLE IF NOT EXISTS `user_repository_relation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联ID：主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `repository_id` BIGINT NOT NULL COMMENT '代码库/扫描源ID',
  `create_user_id` BIGINT DEFAULT NULL COMMENT '创建人用户ID',
  `create_user_name` VARCHAR(128) DEFAULT NULL COMMENT '创建人用户名',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_repository_relation` (`user_id`, `repository_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户代码库关联：用户与其可使用代码库的绑定关系';


-- 普通索引
CREATE INDEX `idx_repository_catalog_application` ON `repository_catalog` (`application`, `enabled`, `deleted`);
CREATE INDEX `idx_issue_query` ON `scan_issue` (`repository_id`, `status`, `create_time`);
CREATE INDEX `idx_task_schedule` ON `scan_task` (`schedule_type`, `schedule_time`);
CREATE INDEX `idx_task_status` ON `scan_task` (`status`, `create_time`);
CREATE INDEX `idx_task_run_status` ON `scan_task_run` (`status`, `update_time`);
CREATE INDEX `idx_user_query` ON `system_user` (`deleted`, `enabled`, `role_code`);
CREATE INDEX `idx_execution_unit_pick` ON `task_execution_unit` (`run_id`, `status`, `id`);
CREATE INDEX `idx_manifest_file_query` ON `task_scan_manifest_file` (`manifest_id`, `file_type`);
CREATE INDEX `idx_user_repository_user` ON `user_repository_relation` (`user_id`);
