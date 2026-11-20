FROM node:22-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV OMS_RELAY_PORT=8788

COPY server.mjs ./server.mjs

EXPOSE 8788

CMD ["node", "server.mjs"]
