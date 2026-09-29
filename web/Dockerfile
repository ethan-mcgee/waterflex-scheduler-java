FROM node:26-slim

# Prisma's engine-detection needs openssl on the PATH at generate time to
# pick the right query-engine binary (debian-openssl-3.0.x on bookworm);
# without it, it silently falls back to the wrong one and the app fails
# at runtime with "libssl.so.1.1: cannot open shared object file".
RUN apt-get update -y && apt-get install -y openssl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npx prisma generate
RUN npm run build

EXPOSE 3000
CMD ["npm", "run", "start"]
