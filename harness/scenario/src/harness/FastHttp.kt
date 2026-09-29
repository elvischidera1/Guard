package harness

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.OkHttpClient

/**
 * OkHttp client with TCP_NODELAY. Without it every loopback POST waits ~40 ms for a delayed ACK
 * (Nagle), which would drown out the SDK's own cost in the initialize/flush benchmarks.
 * Both SDK builds under test get this same client.
 */
fun fastHttpClient(): OkHttpClient = OkHttpClient.Builder().socketFactory(object : SocketFactory() {
    private val d = getDefault()
    private fun Socket.nd() = apply { tcpNoDelay = true }
    override fun createSocket(): Socket = d.createSocket().nd()
    override fun createSocket(h: String, p: Int): Socket = d.createSocket(h, p).nd()
    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = d.createSocket(h, p, l, lp).nd()
    override fun createSocket(h: InetAddress, p: Int): Socket = d.createSocket(h, p).nd()
    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = d.createSocket(h, p, l, lp).nd()
}).build()
