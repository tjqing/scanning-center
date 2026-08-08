# 扫描中心

基于 Spring Boot 2.0.x、Java 8、MyBatis XML、MySQL 8/H2 和 Vue 2 的代码与文档扫描中心。

## 本地启动

后端开发环境默认使用文件型 H2，无需预先安装 MySQL：

```bash
mvn spring-boot:run
```

前端：

```bash
cd web
npm install
npm run serve
```

访问 `http://localhost:8081`。后端接口地址为 `http://localhost:8080/api/v1`，H2 控制台为 `http://localhost:8080/h2-console`。

## MySQL 8 启动

先创建数据库并执行 `src/main/resources/schema.sql`，再配置环境变量：

```text
DB_URL=jdbc:mysql://localhost:3306/scanning_center?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USERNAME=...
DB_PASSWORD=...
```

使用生产配置启动：

```bash
java -jar target/scanning-center-1.0.0.jar --spring.profiles.active=prod
```

## 支持能力

- 普通关键字、正则扫描规则；
- Git 仓库与上传文件/ZIP；
- Java、JavaScript、Vue、XML、YAML、JSON、Markdown、SQL、文本、Word、PDF；
- 异步执行、进度、取消、重新扫描；
- 结果汇总、问题定位及问题状态处理；
- AI 规则数据模型与界面已预留，启用实际模型调用前需实现企业 AI 网关适配器并完成数据外发审批。
