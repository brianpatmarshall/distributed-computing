# Constants Catalog Frontend - Dockerfile
# =======================================
# Multi-stage build for the React application.
# Stage 1: Build the application with Node.js
# Stage 2: Serve with nginx

# Build stage
FROM node:20-alpine as builder

WORKDIR /app

# Copy package files first for dependency caching
COPY package*.json ./

# Copy npm config if present (Artifactory registry)
COPY .npmr[c] ./

# Install dependencies
RUN npm install

# Copy source files
COPY . .

# Build the application
RUN npm run build

# Runtime stage
FROM nginx:alpine

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built assets from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Expose port 80
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
