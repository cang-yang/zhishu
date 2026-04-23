FROM node:20-alpine AS builder

WORKDIR /app

RUN corepack enable

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY .env ./.env
COPY .env.prod ./.env.prod
COPY .env.test ./.env.test
COPY build ./build
COPY public ./public
COPY src ./src
COPY index.html tsconfig.json uno.config.ts vite.config.ts eslint.config.js tree.py ./
COPY packages ./packages

RUN pnpm install --frozen-lockfile
RUN pnpm build

FROM nginx:1.27-alpine

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
