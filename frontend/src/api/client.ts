import axios from "axios";

const api = axios.create({
  baseURL: "/api",
  timeout: 30000,
  headers: {
    "Content-Type": "application/json",
  },
  withCredentials: true,
});

// 401 é tratado pelo router guard, não pelo interceptor

export default api;
