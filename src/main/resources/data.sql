MERGE INTO scan_rule (rule_code,rule_name,description,rule_type,target_type,risk_level,match_type,match_content,case_sensitive,issue_description,suggestion,enabled,deleted)
KEY(rule_code) VALUES ('SECRET_KEYWORD','敏感关键字示例','检测代码中的明文密码关键字','NORMAL','ALL','HIGH','KEYWORD','password=',FALSE,'发现疑似明文密码','请改用环境变量或密钥管理服务',TRUE,FALSE);
MERGE INTO system_user(username,display_name,application,role_code,description,enabled,deleted) KEY(username)
VALUES('admin','系统管理员','ALL','ADMIN','系统内置管理员',TRUE,FALSE);
