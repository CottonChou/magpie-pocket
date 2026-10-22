package com.magpie.pocket.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class MagpieCookieJar : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = cookieStore.getOrPut(host) { mutableListOf() }
        synchronized(existing) {
            for (newCookie in cookies) {
                existing.removeAll { it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path }
                existing.add(newCookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = cookieStore[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(cookies) {
            // Filter expired cookies
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }
    }

    fun clear() {
        cookieStore.clear()
    }
}
