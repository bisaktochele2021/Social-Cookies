package dev.daymond.socialcookies

class FloatingService1 : FloatingService() {
    override fun getNotificationId(): Int = 101
    override fun getDataDirectorySuffix(): String = "account1"
    override fun getInitialX(): Int = 50
    override fun getInitialY(): Int = 100
}