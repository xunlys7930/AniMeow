package com.animeow.app.data.remote

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

class RemoteTransportFailureTest {
    @Test fun connectionErrorsKeepTheCauseAndShowAnActionableMessage() {
        val original = ConnectException("Failed to connect to /192.0.2.1:3000")
        val displayed = remoteTransportFailure(original)
        assertSame(original, displayed.cause)
        assertEquals("暂时连接不上服务，请稍后重试", displayed.message)
        assertFalse(displayed.message!!.contains("192.0.2.1"))
        assertTrue(remoteTransportFailure(SocketTimeoutException()).message!!.contains("超时"))
    }

    @Test fun httpAndCancellationSignalsAreNotReclassifiedAsNetworkFailures() {
        val response = RemoteHttpException(403, "permission denied")
        assertSame(response, remoteTransportFailure(response))
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, remoteTransportFailure(cancellation))
    }
}
