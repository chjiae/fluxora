# Fluxora Web — 前端 SPA（Vue.js + Nginx 静态文件服务）
#
# 依赖缓存策略：
# Layer 1: 仅复制 package.json + lockfile，npm ci 安装依赖（缓存层）
# Layer 2: 复制源码并构建（源码变更只触发 vite build，不重新安装）
FROM node:22-alpine AS build
WORKDIR /app

# ---- 依赖缓存层：仅复制包清单 ----
COPY package*.json ./
RUN npm ci

# ---- 源码层：复制全部源码并构建 ----
COPY . .
RUN npm run build

# ---- 运行时层：Nginx 静态文件服务 ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
