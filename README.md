# 🧠 Java DNS Relay Server 实现

这是一个使用 Java 编写的基于 UDP 协议的 DNS 中继服务器（DNS Relay），用于课程设计或网络协议实验。支持本地数据库优先解析、黑名单拦截、转发未命中查询，并支持多线程、详细调试输出、DNS 报文手动构造、抓包验证等功能。

---

## 📁 项目结构

```

DNS\_Relay/
├── src/
│   └── main/
│       ├── java/org/example/DNSRelay.java
│       └── resources/dnsrelay.txt
├── out/             ← 编译输出目录
└── README.md

````

---

## ⚙️ 环境要求

- Java JDK 8 及以上（推荐 JDK 17）
- 命令行终端（Windows CMD / PowerShell / Linux Terminal）
- 可选：Wireshark（抓包分析 DNS 报文）

---

## 🚀 编译与运行

```bash
cd DNS_Relay

# 1. 创建输出目录（如未存在可忽略报错）
mkdir out

# 2. 编译 Java 文件
javac -d out src\main\java\org\example\DNSRelay.java

# 3. 拷贝数据库文件到输出目录
copy src\main\resources\dnsrelay.txt out\
````

### ▶️ 启动 DNS Relay 服务（基础/默认模式）

```bash
java -cp out org.example.DNSRelay dnsrelay.txt
```

---

## 🧠 高级功能与详细运行方法

本程序支持丰富的高级功能，均可通过命令行参数或配置直接启用，具体如下表：

| 功能               | 运行/配置方式                                 | 验证命令                                                                                                | 预期效果说明                                               |
| ---------------- | --------------------------------------- | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| **调试模式**         | 启动时加 `-d`                               | `java -cp out org.example.DNSRelay -d dnsrelay.txt`                                                 | 控制台打印每次 DNS 查询及处理路径                                  |
| **详细调试模式**       | 启动时加 `-dd`                              | `java -cp out org.example.DNSRelay -dd dnsrelay.txt`                                                | 控制台额外输出响应数、RCODE、异常等详细信息                             |
| **指定上游 DNS 服务器** | 启动时加 DNS IP 参数                          | `java -cp out org.example.DNSRelay -d 8.8.8.8 dnsrelay.txt`                                         | 所有未命中请求转发到 8.8.8.8                                   |
| **指定本地数据库文件**    | 启动时自定义文件名                               | `java -cp out org.example.DNSRelay -d mydb.txt`                                                     | 加载 mydb.txt 作为本地域名数据库                                |
| **多线程并发**        | 默认启用，无需特别命令                             | 多开终端同时运行多条 `nslookup`                                                                               | 所有请求均能快速独立返回，控制台打印多行日志                               |
| **拦截功能**         | 在 `dnsrelay.txt` 添加 `0.0.0.0` 映射        | `nslookup www.666.com 127.0.0.1`                                                                    | 控制台输出“Returning NXDOMAIN”，客户端显示“Non-existent domain” |
| **本地优先解析**       | 在 `dnsrelay.txt` 添加目标域名和IP              | `nslookup www.bupt.com.cn 127.0.0.1`                                                                | 控制台和客户端直接返回配置的 IP，不转发                                |
| **外部 DNS 转发**    | 查询未在本地表的域名                              | `nslookup www.google.com 127.0.0.1`                                                                 | 控制台输出“Forwarding...”，客户端收到真实 IP                      |
| **支持 IPv6 响应**   | 查询如 `nslookup www.google.com 127.0.0.1` | `nslookup www.google.com 127.0.0.1`                                                                 | 控制台可见 A/AAAA 多条，客户端有 IPv4+IPv6 地址                    |
| **抓包分析**         | 启动 Wireshark，过滤 `udp.port==53`          | 任意 nslookup 查询                                                                                      | Wireshark 可见所有 DNS 报文、响应结构                           |
| **错误处理**         | 启动时指定不可用 DNS（如 192.0.2.1）               | `java -cp out org.example.DNSRelay -d 192.0.2.1 dnsrelay.txt`<br>`nslookup www.baidu.com 127.0.0.1` | 控制台“returning SERVFAIL”，客户端“Server failed”           |

---

### 🏷️ 高级模式实操示例

#### 1. 启动详细调试 + 指定上游 DNS

```bash
java -cp out org.example.DNSRelay -dd 8.8.8.8 dnsrelay.txt
```

#### 2. 并发测试

多开终端，分别执行：

```bash
nslookup www.bupt.com.cn 127.0.0.1
nslookup www.666.com 127.0.0.1
nslookup www.google.com 127.0.0.1
```

#### 3. 黑名单拦截功能

`dnsrelay.txt` 添加：

```
0.0.0.0 www.666.com
```

测试：

```bash
nslookup www.666.com 127.0.0.1
```

#### 4. 本地数据库命中

`dnsrelay.txt` 示例：

```
114.255.40.66 www.bupt.com.cn
```

测试：

```bash
nslookup www.bupt.com.cn 127.0.0.1
```

#### 5. 上游转发与 IPv6 支持

```bash
nslookup www.google.com 127.0.0.1
```

#### 6. 错误与超时处理（以管理员权限打开IDE（端口53的情况下））

```bash
java -cp out org.example.DNSRelay -d 192.0.2.1 dnsrelay.txt
nslookup www.baidu.com 127.0.0.1
```

#### 7. Wireshark 抓包分析

1. 打开 Wireshark，选择网络接口；
2. 设置过滤器：`udp.port==53`
3. 执行上面任意 `nslookup`
4. 在 Wireshark 可见 DNS 查询和响应细节

---

## 🧪 使用 nslookup 快速测试 DNS Relay

```bash
# 命中本地数据库（返回 IP）
nslookup www.bupt.com.cn 127.0.0.1

# 拦截域名（返回 NXDOMAIN）
nslookup www.666.com 127.0.0.1

# 中继转发（转发至上游 DNS）
nslookup www.google.com 127.0.0.1

# 查询 AAAA（自动转发处理）
nslookup -query=AAAA www.bupt.com.cn 127.0.0.1
```

---

## 📜 调试模式输出示例

```text
Debug mode level 2 enabled.
Loaded 3 entries from dnsrelay.txt
DNS Relay server started on port 53, forwarding queries to DNS server 8.8.8.8

Received query from 127.0.0.1:52917 for domain: www.bupt.com.cn (Type 1)
 -> Domain found in local database. Returning IP: 114.255.40.66

Received query from 127.0.0.1:52918 for domain: www.666.com (Type 1)
 -> Domain is blocked in local database. Returning NXDOMAIN.

Received query from 127.0.0.1:52919 for domain: www.google.com (Type 1)
 -> Domain not in local database or not an A query. Forwarding to DNS server 8.8.8.8
 <- Response from DNS server: Answers=2, RCODE=0
```

---

## 🧠 拓展建议

* 支持缓存和 TTL 过期
* 增加更多 DNS 记录类型（AAAA、MX、CNAME）
* 使用配置文件灵活管理端口、线程数等
* 增加 Web 界面或命令行管理接口
* 统计日志、响应时延、命中率等

---

## 📎 License

MIT License / 教学优先 / 自由使用




