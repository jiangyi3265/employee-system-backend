# Employee System Backend

员工系统后端服务，基于 RuoYi/Spring Boot 构建，负责登录认证、权限控制、系统管理、员工档案、客户档案、商品、采购、报价等业务数据接口，是管理后台和用户端的统一 API 服务。

## 技术栈

- Java 8
- Spring Boot 2.5.x
- Spring Security / JWT
- MyBatis / PageHelper
- MySQL / Druid
- Redis
- Maven 多模块工程
- Swagger / Fastjson2 / Apache POI

## 关联仓库

| 子项目 | 仓库 | 说明 |
| --- | --- | --- |
| 后端服务 | [employee-system-backend](https://github.com/jiangyi3265/employee-system-backend) | 提供认证、权限、系统管理和员工档案等 API |
| 管理后台 | [employee-system-admin](https://github.com/jiangyi3265/employee-system-admin) | 面向管理员的 Web 管理端 |
| 用户端 | [employee-system-app](https://github.com/jiangyi3265/employee-system-app) | 面向员工、客户和移动端场景的应用 |

## 快速启动

```bash
# 1. 创建 MySQL 数据库并导入初始化脚本
# 示例脚本位置：sql/ry_20250522.sql

# 2. 修改数据库、Redis 等配置
# ruoyi-admin/src/main/resources/application-druid.yml
# ruoyi-admin/src/main/resources/application.yml

# 3. 编译后端
mvn clean package -DskipTests

# 4. 启动服务
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认服务地址通常为 `http://localhost:8080`，实际端口以 `application.yml` 为准。

## 简历描述示例

负责员工系统后端服务建设，基于 Spring Boot、Spring Security、MyBatis 和 Redis 实现统一认证授权、角色权限控制、员工档案管理和业务数据接口，为管理后台与用户端提供稳定的 API 支撑。
