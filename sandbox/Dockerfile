# Coding Agent 默认沙箱镜像
#
# 设计取舍：做成「一把梭」的通用镜像（JDK + Node + Python + 常用构建工具），
# 体积偏大但开箱即用——MVP 阶段让用户少配一步比省镜像体积重要。
# 项目可通过 sandbox_image 字段换成自己的镜像。

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        curl \
        wget \
        ca-certificates \
        build-essential \
        make \
        unzip \
        zip \
        jq \
        tree \
        ripgrep \
        fd-find \
        # JDK（maven/gradle 构建用）
        openjdk-17-jdk-headless \
        maven \
        # Python
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Node 20 LTS —— 走 NodeSource 而不是 Ubuntu 仓库，版本更稳
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# 常用包管理器的缓存目录先建好，避免 agent 第一次装依赖时因权限失败
RUN mkdir -p /workspace \
    && npm config set cache /workspace/.npm-cache --location=global \
    && pip3 config set global.cache-dir /workspace/.pip-cache

# 用非 root 身份跑 agent 命令，即便容器被突破也拿不到 root
RUN useradd --create-home --uid 10001 --shell /bin/bash agent \
    && chown -R agent:agent /workspace

USER agent
WORKDIR /workspace

# 默认起一个 sleep，由宿主机通过 docker exec 进来执行命令
CMD ["sleep", "infinity"]
