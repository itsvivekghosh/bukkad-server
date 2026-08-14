# 🍔 Bhukkad - Food Delivery Platform

A comprehensive food delivery platform built with Spring Boot, similar to Swiggy and Zomato.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-green)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)
![Redis](https://img.shields.io/badge/Redis-7.0-red)
![License](https://img.shields.io/badge/License-MIT-yellow)

> **Documentation:** Full onboarding and runbooks live in **[docs/](./docs/README.md)** — getting started, Docker, Kubernetes, API usage, and operations.

## 📋 Table of Contents

- [Documentation](./docs/README.md)
- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Architecture](#-architecture)
- [Prerequisites](#-prerequisites)
- [Quick Start](#-quick-start)
- [Docker Setup](#-docker-setup)
- [API Documentation](#-api-documentation)
- [API Endpoints](#-api-endpoints)
- [Environment Configuration](#-environment-configuration)
- [Logging](#-logging)
- [Caching](#-caching)
- [Testing](#-testing)
- [Contributing](#-contributing)
- [License](#-license)

## ✨ Features

### Customer Features
- 🔐 User registration & JWT authentication
- 🔍 Browse restaurants by cuisine, rating, location
- 🍕 Search restaurants and menu items
- 🛒 Cart with customization options
- 🎫 Apply coupons and discounts
- 💳 Multiple payment methods (COD, UPI, Card)
- 📍 Real-time order tracking
- 🏠 Multiple delivery address management
- 📜 Order history and reordering
- ⭐ Rate and review restaurants
- 💰 Loyalty points and wallet

### Restaurant Owner Features
- 🏪 Restaurant profile management
- 📝 Menu and category management
- 🎨 Item customization options (Size, Toppings, etc.)
- 📦 Order management and status updates
- 📊 Restaurant analytics
- 🔔 Real-time order notifications

### Delivery Agent Features
- 🚗 Accept/reject delivery requests
- 📍 Real-time location tracking
- 📋 Delivery status updates
- 💵 Earnings tracking
- 📜 Delivery history

### Platform Features
- 🔒 Role-based access control (Customer, Owner, Agent, Admin)
- 📊 Comprehensive logging with JSON format
- ⚡ Redis caching for optimal performance
- 📝 Swagger/OpenAPI documentation
- 🏥 Health check endpoints
- 📈 Performance monitoring
- 🐳 Docker support

## 🛠 Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 17 | Language |
| Spring Boot | 3.2.0 | Framework |
| Spring Security | 6.x | Authentication & Authorization |
| Spring Data JPA | 3.2.0 | Database ORM |
| MySQL | 8.0 | Primary Database |
| Redis | 7.0 | Caching Layer |
| JWT (jjwt) | 0.11.5 | Token Authentication |
| SpringDoc OpenAPI | 2.3.0 | API Documentation |
| Logback | 1.4.x | Logging |
| Logstash Encoder | 7.4 | JSON Log Format |
| Maven | 3.9+ | Build Tool |
| Docker | 24+ | Containerization |
| Lombok | 1.18.x | Boilerplate Reduction |

## 🏗 Architecture