package si.safeer.tv.os

import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

private fun preveri(pogoj: Boolean, sporocilo: String) = check(pogoj) { sporocilo }
private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
private fun odtis(c: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

private fun varint(v: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var n = v
    while (true) { val b = (n and 0x7F).toInt(); n = n ushr 7; if (n == 0L) { out.write(b); break }; out.write(b or 0x80) }
    return out.toByteArray()
}
private fun polje(st: Int, b: ByteArray) = varint(((st shl 3) or 2).toLong()) + varint(b.size.toLong()) + b
private fun stevilo(st: Int, v: Long) = varint((st shl 3).toLong()) + varint(v)

/** DeviceAuthMessage { response: AuthResponse { 1 signature, 2 device cert, 3 intermediates, 4 alg, 5 nonce, 6 hash } }. */
private fun odgovor(podpis: ByteArray, naprava: ByteArray, vmesna: List<ByteArray>, nonce: ByteArray?, zgoscevanje: Int = 1, algoritem: Int = 1): ByteArray {
    var r = polje(1, podpis) + polje(2, naprava)
    vmesna.forEach { r += polje(3, it) }
    r += stevilo(4, algoritem.toLong())
    if (nonce != null) r += polje(5, nonce)
    r += stevilo(6, zgoscevanje.toLong())
    return polje(2, r)
}

// Izmisljena veriga za preizkus (koren -> vmesno -> naprava, potrdilo TLS in podpis naprave nad nonce || TLS s SHA-256,
// tako kot JBL BAR 300 - izmerjeno 25. 10. 2026). Narejena z openssl (tools/cast_preizkusna_veriga.sh); zasebni
// kljuci niso v repozitoriju. Potrdilo naprave pravega zvocnika NI tu: v imenu nosi MAC naprave.
private const val PREIZKUS_KOREN = "MIIDMTCCAhmgAwIBAgIBATANBgkqhkiG9w0BAQsFADA6MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHjAcBgNVBAMMFVByZWl6a3Vzbmkga29yZW4gQ2FzdDAeFw0yNjEwMjUxNjI4MTBaFw00NjEwMjAxNjI4MTBaMDoxGDAWBgNVBAoMD1NhZmVlciBwcmVpemt1czEeMBwGA1UEAwwVUHJlaXprdXNuaSBrb3JlbiBDYXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAydLuPuQQT1LVzMEmbbotUOqVYZyCIaTD3l0m7qQN3cEzTdmRSiIndDh5WSO5L6zcLpZF8dYo+jxVP0R1z8xCCWzWh6XgUTfWE9zCWsMo6RHAVzOH3GgAiMYFnZPMmRXy0HZVX1exBBKbqZDg/21O+Mo6zorgHVDWt8JPYuYjLsavttYImtixdTWgkunadVrLzDxAYz3xGre0KcMb+bp72qPvOyY1UiuCu0cYVC36Ovt3aTYeo6M76e0OyWQ7Zp1s7+BrRkr3lqSRKRh4khzjRRIdQcMzPbSHMTeM+/oPJk/ewZN04VLIFPWU6Gi9V54aqaGQB+/ZJ52/Vg2gwPrzFwIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUH9X8kcrlVTARI+6YtWnSIH/TiGowDQYJKoZIhvcNAQELBQADggEBAGe0PjL6kMi0UikyHFB5ylspEdYAspKQGgk3ZC92QnFNfoddl6IhRbQMc6WD8FTTZX1wAlZVsFFizW3SMf1G8hHt13XHW73hRhlpuaG18cvnfr1btV7Fjq80FCjl/S64uVI/SYMWvpeonzHisu3esQvre0msp/aCSBlm+PImPYKphzruxd3eLpwNCej3nLmkFDnMPGzxbOy5adB+KFiRTISnK/jGAAR/6w5LwGxhAu8BMu3VZsNRPpAt0jD27kJSo6XgEducv+25euU3cV97Q1H1ogM0+X+Kniu5Xw/CT2UfYTfL8Zs/I8pMECL0p7aWSsP6ie4IGuRbcPvHtrjjfYY="
private const val PREIZKUS_VMESNO = "MIIDVjCCAj6gAwIBAgIBAjANBgkqhkiG9w0BAQsFADA6MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHjAcBgNVBAMMFVByZWl6a3Vzbmkga29yZW4gQ2FzdDAeFw0yNjEwMjUxNjI4MTFaFw00NTEyMjQxNjI4MTFaMDsxGDAWBgNVBAoMD1NhZmVlciBwcmVpemt1czEfMB0GA1UEAwwWUHJlaXprdXNubyB2bWVzbm8gQ2FzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAPrchZaWN7mkomwxmCtS5Wr+sMjzHsCAObvQmmmA+cvKLoAOec1FIjt0ItL1o1GyEFYKBsVzEDfxc6RBlyatVT4n6uH35N7A2ausrzOvKLqrQ3KxwYS0SsrfcBbhTW2P0fWAQ3vAzyB2K2GVZ5ORknIRrsVQ/9TPIKzof0C3Yfoy2INz0TAR0my/AzRd2DYEekTIgfgHK3XxolqQOMLTnJtONG0o7NojksnN7B0ye4bnsUMkqHjk2D3/91vLdA8potGGhfGVySsfgA0CNxEgTsjeV6nrbAFLkzqNPbHvKqWicne/P/t03b82HOXyrL8tGFQkb2kVXFL+Un5pwRKDjEsCAwEAAaNmMGQwEgYDVR0TAQH/BAgwBgEB/wIBADAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFAZx9AHo3G3P6bIqY/eTR7InM/g1MB8GA1UdIwQYMBaAFB/V/JHK5VUwESPumLVp0iB/04hqMA0GCSqGSIb3DQEBCwUAA4IBAQCQ/hGsPHEAoYDoLipZiMj0dp1oP7d4+18LPya0vt5BkXJ6r/MZHEwI+280Eu/8CsHT3S2IoW5FuRxyc7gimLyykNfMEEKtn4K6WLnsC8D1JxVpktRJcuswDl85f95Govb7YX2ztkAxnfjF+973Ufiu/YjlMN7zQGEDiv5AubAjMIC9QD3C9470moxZ/IOFSza+7mP7cDiWlS3PvvSJl8Pd9zfEK2yxs37Hg6SVhQ3ei5eiF0V2Uv3t49V3/NSL3BxCDcRZzD+vEY7rdC9mRsNXwsqXIMvd9Bt6aJIhVicQMyCUTkb7VOlX99GNGdj/BKs3TrOrU5/0Ipp5hqqV1W1D"
private const val PREIZKUS_NAPRAVA = "MIIDSDCCAjCgAwIBAgIBAzANBgkqhkiG9w0BAQsFADA7MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHzAdBgNVBAMMFlByZWl6a3Vzbm8gdm1lc25vIENhc3QwHhcNMjYxMDI1MTYyODEyWhcNNDUwOTE1MTYyODEyWjAdMRswGQYDVQQDDBJQcmVpemt1c25hIG5hcHJhdmEwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCogKqP2eyN4aSdVp2vQMW75HcDorL5q8SWkVx8b6zBA3ccQZiO+l0E3AH1fIQcn/xXXNNd4cG4zRP/QSluelomZqpLfqMe2OWi8kN0eHO7fgB0efZWd/G/gZt1HSWi74prPBXTlXDAmQJfS8zNtKXJ0FHxCas3lLwehmmIqSyfWIHL+nMG9++hfbAKY/HS/48ePSy9MS1UBdNMVGRmrZnkEhUGcGDyX1/vdRt4icCaXXJKOtMP3CD3w9+peqvc6L7HzAN+9BrUBHptXtMsG35m645VMiC9Hv15tpbIMWLpzXcR0KWc1PdUV96cMT7nem4LiavD0idl9ZWFhwiprhXHAgMBAAGjdTBzMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUFBwMCMB0GA1UdDgQWBBRmFBUOEDNDcZtFS/RZevHzbLUPXTAfBgNVHSMEGDAWgBQGcfQB6Nxtz+myKmP3k0eyJzP4NTANBgkqhkiG9w0BAQsFAAOCAQEAq6SgS0k5F/m4v18iuvtbnWJFuqTCEDAd2ZysAa8T6hdE3oKnrgs6bER0NfXysZsJQYL6seZLewXnRxrtxtWkRZs73zGFZoVCAcg9tl7O1f0JA2FKlgYY1zdl7heT8ElseniDFTLi/vgmVfaAcHxfXr9LU5++bCCTZWOTFoCCV6G7IqKiA+V3hG63XZfIC1wwXeES8Z8TNchSk5ygz3shulk2nNp2DtOp8iUYDWb8qv5xMLFfmFFW85JasXcjVOBpfELmAGQLcFbmoh7tJYahP0ASBNHD10siiB3YDAnWNC4wjzh6Zeo83n5ugUK2W2ebWg3zmgSv7yZ6mlRh3RaOdQ=="
private const val PREIZKUS_TLS = "MIIDEzCCAfugAwIBAgIUenVRsCBU/ZyV3U0KQZIivQc2IxcwDQYJKoZIhvcNAQELBQAwGTEXMBUGA1UEAwwOcHJlaXprdXNuaS10bHMwHhcNMjYxMDI1MTYyODEzWhcNMjYxMDI3MTYyODEzWjAZMRcwFQYDVQQDDA5wcmVpemt1c25pLXRsczCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAL2yXu02XblDab0W9yGaMuyUNdGWDHFc+Lae5B06VpegGBlJ7Kx06qxDSDiWtJsgR7+bJ61t6OZjKw+C6BWkKR7awe6voTl/3eKRDwAdlrG1ZoNz8dK+X2y/qIRsVyiyq+Ykal90QSleCA9lsW+1lZC3TmzYZvWghjGZQ3SvxXEptWBAVXTGifxyM+E8yEEqFU5tA0uPcvyx3XSWS5zI8lYAnkTIp3B23A50o4G/u+6R2fBapfV1h+8rQDO2cGJG3TzniY5LVbZVy4DPHL6ckgM0UYig/zFthNHm1olL96nU/TFGuliiVoSp7IKgV9pT3TwxernAGrSvDLueKFbXZPUCAwEAAaNTMFEwHQYDVR0OBBYEFMDUcFA1aUAXQZ4KH51HeDCjd7+LMB8GA1UdIwQYMBaAFMDUcFA1aUAXQZ4KH51HeDCjd7+LMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJLqIIrvp/oKB0SkEK8jGQYERv2yr30cbtOfIwJO/vHMMvSnSW5h51/0NalurzVlv5+EWMOZKJJ9Zn8rqAbAjVZI3R4L2NPYhNbhjfqcuoetxDoBB4+M2b73zCnjTJTIgACpJwyFuSXgGzP8ddcj5ZujIxaVZKDXYpqL8EJs31HCGZpb7PCk/nhrzoldsJ6AwbuzFUQYzVsANjj9+Sx2QHjxky2v+WlLH0v09kL8RP+5r9hXXMTor0crRn+jp4X3X1BhS+o++qq1cpqTs3HRyl81EsoNBjzIDEPNjvcMaVRpVRjRDX2oGFPUoIeClAEv6fE5RhO9nV6oL3/RVV4Nq9g="
private const val PREIZKUS_NONCE = "MDEyMzQ1Njc4OWFiY2RlZg=="
private const val PREIZKUS_PODPIS = "cNoX/NpIFASCA5gK463IiIKfQyWA74sgTjESjDZs7NRqKsLq3ql0w/2NnL6ucwR5ai5mbyfLtPGJYB30NB1PKbafrk3eQlbgrXKevYpdB6z3RTjpeeRXmVuhNx98VJv+kBn0pZIpOuvU1KZi/+QDm8oNSAj31vAHw3YVMvUVzJsaGnDclcFdq2siAcse2AzFrlAzGgNna9N7fEouv4xvjLvLkUi6scwaQ6Fg14AgKs0Y+lkWo851VIDd2YalYIWtVBDKMpHYbeWDpIsIUvMbjR3ae9UVBviJelVcdgq6ZExI2H3l8geVRA9R089b5vY/ECQw/Yzkh0f/Vo/7MpePRA=="
private const val PREIZKUS_PODPIS_BREZ_NONCE = "f4WDex1fv6uZwEeZUQvVrh+pVq5ZQY3j+vw/RyLybTPm4TOJn/H5LB8/Pgxb7GbMg7OiLcIl73vuJF+uZfQfraEyirRfOJ27FXf3842oa+P9lNYO3TLNVBFjd4u+v4cFT2vVPLHMpbYm3zunXI515642vlAe+fTpwULPMzTa/ymgmkt1r16CuI24Xm7XQJMu+pJFg4uylyT9Y+LRmQ8dAYluUNwdI8WaV/vhoOWYSIi+9souDqY8jMvH10JewoQEQ5OBH+sosyD5sCJVSPr073Wzkay7P9aoPkgF033AbKVNSABBjbXIhZwq4LnQLYK+rzoESMZ4FFg875z08kg8Zw=="

// CA, ki sme tudi podpisovati (digitalSignature), podpisan od izmisljenega korena: kot »naprava« ga ne sprejmemo.
private const val PREIZKUS_CA_PODPISNIK = "MIIDWzCCAkOgAwIBAgIBBDANBgkqhkiG9w0BAQsFADA6MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHjAcBgNVBAMMFVByZWl6a3Vzbmkga29yZW4gQ2FzdDAeFw0yNjEwMjUxNzA2NTlaFw00NTA5MTUxNzA2NTlaMEAxGDAWBgNVBAoMD1NhZmVlciBwcmVpemt1czEkMCIGA1UEAwwbUHJlaXprdXNuaSBDQSwga2kgcG9kcGlzdWplMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuRrqjm4OYgYPiadcnTCZXna4rtSl4TgVQy650Y4wBw1Qq3J3PpLxjlpdjUI+KRCNhgL+HS6BUZ412Z/Pnp4GLQRY36/PitthAQhcn4j/Yc4KMR/KL12A90T7AWUF6ND+julcAoAM4b0UG9d7MydAJmF3Zoq4Ib3/oRbJDzQjMF3S+6Y4tKPM5mLy1uM2aIipy3CmIZG3U8SrL4jCy78002wO2yCfBAD4m6K9/5oCJRODiDD3ohr8mK9wdDO5WyldusOLAAb8mHY0syT5F8Em58qZdHkdmN7LKT2E/if9xGfd4RuB9l207ncKxvM1KxuzR9Dpu3x3VCxr8yr8sR11+wIDAQABo2YwZDASBgNVHRMBAf8ECDAGAQH/AgEAMA4GA1UdDwEB/wQEAwIChDAdBgNVHQ4EFgQU1N6dWNus7LAXS3ppX6wZjMTMRJUwHwYDVR0jBBgwFoAUH9X8kcrlVTARI+6YtWnSIH/TiGowDQYJKoZIhvcNAQELBQADggEBAHPaIYRHwqVmZJrgDcLnKtk7SdER+pzJqM0i0nUDynIN+fXzw3was2E2Q8OFqyhweluxuAC3h+gjFrVGivgauFjV/p8ZPjhbo1q4SbCCyamp/dJS8jBGtQLKF9LzRZakW91ml0TBubChCR/qJmhabVJ3EH7pwZz5kUmsSGTtJo/BrSfFZ+YoGxLHG+2/ZU8VTN53ha+7Vh/+eGFAdi4nirj/h/LNWfwYuiPVIZKpPtgAsG7piOZ5bPJQo2c+iha+0TV09Bf6qstEtniaiKyMlrlpq8lf/2sp1Rse4mMjfXARDrp/rEuueGuLmYLWxRPVcoxTu1fYLlYtGYjG77XFCYM="
private const val PREIZKUS_PODPIS_CA = "Nmn9h6yvj+2aG0Ni3sHcAd7PBAfCGvsOt649K94yBGuAW96qa92olBjkAewwoDN8XKM9/7E08qH3pmc9th8GhspUbmL6/icbRXm9EaR6GwenzdCzNb9fFdyuuOHjOqeMVPyR5LwPRMR/nZu5enWs6rNWM2yWzIk9mJ6ndmB90KfNpdepcrW3LdvnRI6iTvHM3uyks+6gbWU/8NPdBfK9dvW9qKRPi7bhADaiIDc+WimmlyKxdaQZn+Y8pFjAPvb/IdNBWevc5E5snm5nzVoLwxI7YtnDOj2jHpQhOBuRJDoyohkPYqMx34dUs6RlkiSCinAhNQLi7VlGabxPKQ+p7Q=="

// Javni vmesni potrdili iz odgovora JBL BAR 300 (25. 10. 2026): skupni vsem napravam tega modela, brez podatkov naprave.
private const val JBL_VMESNO_HARMAN = "MIIEJjCCAw6gAwIBAgIUAMrQUTzcUZzB2Lf99P2ritIuVwQwDQYJKoZIhvcNAQELBQAwejELMAkGA1UEBhMCVFcxDzANBgNVBAgMBlRhaXdhbjEQMA4GA1UEBwwHSGlzbmNodTEVMBMGA1UECgwMTWVkaWFUZWsuSW5jMQ0wCwYDVQQLDARDYXN0MSIwIAYDVQQDDBkiTWVkaWFUZWsuSW5jIENhc3QgQXVkaW8iMB4XDTIxMDgwOTA2MzIwMFoXDTQxMDgwNDA2MzIwMFowgaQxCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDQTETMBEGA1UEBwwKTm9ydGhyaWRnZTEoMCYGA1UECgwfSEFSTUFOIEludGVybmF0aW9uYWwgSW5kdXN0cmllczENMAsGA1UECwwEQ2FzdDE6MDgGA1UEAwwxIkhBUk1BTiBJbnRlcm5hdGlvbmFsIEluZHVzdHJpZXMgSkJMX0JBUl8zMDBfUFJPIjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOUyNk0N69V5ICzFRC5luyTP/KGdb57D9rROZy0d/Vbmxvxk0YWwTSFwpgr5xM4n4Wg2mqhP85YRhYzt9tAwSDi6otIkqMp2ZCgZOJoAMKXLFD5S7ZXfvTHFeReh9ZS3j9oSEAYAML3rvH/eSPKYbKqWHq2ADBKuhvJPAT00S6zV6DV3xI/xcUCmrmAS52EXwNzdZWfMmGpLCy5c6mxFK370GB7z4fIVIXTP4UQ5Zw2flkOmYpIAaJUhl9oZogEq1STsvBIToUNLwP7m9EuNXxFmNiNaK/nY7ubbCuypuN44Fo3CUdTOCm9/jP9O/r69bZBhtpAEuxF9NUfzgAeqIc0CAwEAAaN5MHcwDwYDVR0TBAgwBgEB/wIBADAdBgNVHQ4EFgQUKeRLPWcHoG8dynUQtMYz1UMeAt0wHwYDVR0jBBgwFoAU6uab1ekHYM/JPliEa1+HPuXVeDowCwYDVR0PBAQDAgEGMBcGA1UdIAQQMA4wDAYKKwYBBAHWeQIFAjANBgkqhkiG9w0BAQsFAAOCAQEAfOyRnYwqyDBpMEE3AfgBMTlGIU0k/B0qDfFw4cxIF22vKyZ25/2fGacIlt1wszm9EnxX3pebgJfZoi0UZbGmigGxonKPPLQU8VCFKM7cpW64DNYA5y4vAzXbWaeUOU1yCFgJEvnAAhn+q2JgRPJhFlNPlqTX1Yl53t0k4tiBbQPb2Ijf+p0TKwfdu4YZcV3B8SbKXxVVpLhRM8i0y7NJdf1sKLRmMfLMwcFeuxrG9guH6PyvVtEKMwXZh2kv2Feh4ALvadgffDbpxCZsWnlmdjJUqVhMoN8aEQkMqUPojOz9GHmcORWNuonYHZUzosI8ecIPL90iQLrEc6/S6YWG9g=="
private const val JBL_VMESNO_MEDIATEK = "MIID5DCCAsygAwIBAgICAOswDQYJKoZIhvcNAQELBQAwdTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxFTATBgNVBAMMDENhc3QgUm9vdCBDQTAeFw0xNzA3MTgwMjQzNDZaFw0zNzA3MTMwMjQzNDZaMHoxCzAJBgNVBAYTAlRXMQ8wDQYDVQQIDAZUYWl3YW4xEDAOBgNVBAcMB0hpc25jaHUxFTATBgNVBAoMDE1lZGlhVGVrLkluYzENMAsGA1UECwwEQ2FzdDEiMCAGA1UEAwwZIk1lZGlhVGVrLkluYyBDYXN0IEF1ZGlvIjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAO4bZ59dKLLNkXUzq7KypIrgUGz1kyVT2nX3CWJrzZhLIzCVhrkfwpMKX0PhsLxzyW2NlnWKnou/l6weyqz1JDz1W5NwcXxUTG1fdn7puxV9W0+slQ4MP3cOQKMvxfhs7CmzoxE5rtgu5xRLcZW/n7qJw9RtrzylE0mIwvqJVSgQgcaqMai2kciIrgdp9yco4zqd2CZKdvmZ6PWUkg7kBNMzRqK/SmH8KfG2VlfCc8CBBUSxEDvKx4LNuWLR4JlWAPYjgU/yS9s4YprIw1g9fTCxoorhp8IoMhx2BHDhKz7hbTvEz4FCGtHiVdmNxAHNCjMdQNWrcw6KJc+gEIIeUC8CAwEAAaN5MHcwDwYDVR0TBAgwBgEB/wIBATAdBgNVHQ4EFgQU6uab1ekHYM/JPliEa1+HPuXVeDowHwYDVR0jBBgwFoAUfJoefd95VLzXzF7KmYZFeWV0KBkwCwYDVR0PBAQDAgEGMBcGA1UdIAQQMA4wDAYKKwYBBAHWeQIFAjANBgkqhkiG9w0BAQsFAAOCAQEARipzZaXhoF/PxgQd1cQOUbRXn1PtUuL9GzPgt/APMma6ChQs6qWL+rgVQBQY+ijmCjFLR2BNjvaRbFePyQruqsAZ4aoIE0WVNUEkpmttNbD9FhX/153Ck27q929wD5GQPkUmIMraibbNio7i18963dZ9KSaX6zSKvjhiv82OPIy2aTTkyf+OEizZfh3J5HJH3L9Fc1kZDIv1vFfPacPv0uWCtNTkRjp/HUoR5sGylP0KJ3T5TCp9E1A01stfPdnhhPKoX+AkWnTeF191qx8DAm3x7OtqoK7D0XUKDl/conRBw7wu4goMyvX/dgc/GO4KUaIc5ipEewXmiBO5PtOKOg=="

fun main() {
    // Okvir: 4 bajti dolzine + CastMessage (bajti kot v meritvenem skriptu v Pythonu, ki je delal z JBL).
    val telo = byteArrayOf(0x08, 0x00, 0x12, 0x01, 0x73, 0x1a, 0x01, 0x72, 0x22, 0x02, 0x6e, 0x73, 0x28, 0x00, 0x32, 0x02, 0x7b, 0x7d)
    val o = CastPravila.okvir("s", "r", "ns", "{}")
    preveri(o.contentEquals(byteArrayOf(0, 0, 0, telo.size.toByte()) + telo), "okvir")
    val r = CastPravila.razcleni(o.copyOfRange(4, o.size))
    preveri(r != null && r.izvor == "s" && r.cilj == "r" && r.ns == "ns" && r.besedilo == "{}" && r.binarno == null, "razcleni")
    val ob = CastPravila.okvir("sender-0", CastPravila.SPREJEMNIK, CastPravila.NS_PRISTNOST, binarno = byteArrayOf(1, 2, 3))
    val rb = CastPravila.razcleni(ob.copyOfRange(4, ob.size))
    preveri(rb != null && rb.besedilo == null && rb.binarno!!.contentEquals(byteArrayOf(1, 2, 3)) && rb.ns == CastPravila.NS_PRISTNOST, "binarno")
    preveri(CastPravila.dolzina(byteArrayOf(0, 0, 1, 0)) == 256 && CastPravila.dolzina(byteArrayOf(0, 0, 0, 0)) == -1 &&
        CastPravila.dolzina(byteArrayOf(0, 1, 0, 1)) == -1 && CastPravila.dolzina(byteArrayOf(-1, -1, -1, -1)) == -1, "meje dolzine")
    preveri(runCatching { CastPravila.okvir("s", "r", "ns", "x".repeat(70_000)) }.isFailure, "predolgo sporocilo")
    // Pokvarjen protobuf iz omrezja: prekratko polje, neznana vrsta, brez imenskega prostora.
    preveri(CastPravila.polja(byteArrayOf(0x22, 0x10, 0x61)) == null, "prekratko polje")
    preveri(CastPravila.polja(byteArrayOf(0x0b)) == null, "neznana vrsta")
    preveri(CastPravila.razcleni(byteArrayOf(0x08, 0x00)) == null, "brez imenskega prostora")
    preveri(CastPravila.polja(ByteArray(11) { -1 }) == null, "predolg varint")

    // Izziv: DeviceAuthMessage { challenge { RSASSA_PKCS1v15, nonce, SHA256 } }.
    val nonce = b64(PREIZKUS_NONCE)
    val ch = CastPravila.polja(CastPravila.polja(CastPravila.izziv(nonce))!![1]!![0] as ByteArray)!!
    preveri(ch[1]!![0] == 1L && (ch[2]!![0] as ByteArray).contentEquals(nonce) && ch[3]!![0] == 1L, "izziv")

    // Pristnost naprave na izmisljeni verigi.
    val koren = CastPravila.potrdilo(b64(PREIZKUS_KOREN))!!
    val tls = b64(PREIZKUS_TLS)
    val zdaj = CastPravila.potrdilo(tls)!!.notBefore.time + 3_600_000L
    val naprava = b64(PREIZKUS_NAPRAVA)
    val vmesno = b64(PREIZKUS_VMESNO)
    fun preverba(odg: ByteArray, n: ByteArray = nonce, t: ByteArray = tls, ura: Long = zdaj, korenine: List<X509Certificate> = listOf(koren)) =
        CastPravila.odgovorPristnosti(odg)?.let { CastPravila.preveri(it, n, t, ura, korenine) }
    val dobro = odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce)
    preveri(preverba(dobro) == CastPravila.Pristnost.OK, "prava naprava")
    preveri(preverba(dobro, n = "fedcba9876543210".toByteArray()) == CastPravila.Pristnost.NONCE, "odgovor na tuj izziv")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), "x".repeat(16).toByteArray())) == CastPravila.Pristnost.NONCE, "drug nonce v odgovoru")
    // Napadalec med napravo in nami ima drugo potrdilo TLS: podpis naprave zanj ne velja.
    preveri(preverba(dobro, t = b64(PREIZKUS_VMESNO)) == CastPravila.Pristnost.PODPIS, "drugo potrdilo TLS")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_BREZ_NONCE), naprava, listOf(vmesno), nonce)) == CastPravila.Pristnost.PODPIS, "podpis brez nonce")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_BREZ_NONCE), naprava, listOf(vmesno), null)) == CastPravila.Pristnost.OK, "starejsa naprava brez nonce")
    preveri(preverba(dobro, ura = CastPravila.potrdilo(tls)!!.notAfter.time + 1_000L) == CastPravila.Pristnost.TLS, "potrdilo TLS poteklo")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, emptyList(), nonce)) == CastPravila.Pristnost.VERIGA, "manjka vmesno")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), vmesno, listOf(vmesno), nonce)) == CastPravila.Pristnost.VERIGA, "CA kot naprava")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_CA), b64(PREIZKUS_CA_PODPISNIK), emptyList(), nonce)) == CastPravila.Pristnost.VERIGA, "CA s podpisovanjem kot naprava")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce, algoritem = 2)) == CastPravila.Pristnost.PODPIS, "PSS")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce, zgoscevanje = 0)) == CastPravila.Pristnost.PODPIS, "SHA-1")
    preveri(CastPravila.odgovorPristnosti(dobro)?.let { CastPravila.preveri(it, nonce, tls, zdaj) } == CastPravila.Pristnost.VERIGA, "Googlov koren izmisljene verige ne sprejme")
    preveri(CastPravila.odgovorPristnosti(byteArrayOf(0x12, 0x05, 1)) == null, "pokvarjen odgovor")
    preveri(CastPravila.odgovorPristnosti(polje(3, stevilo(1, 0))) == null, "AuthError")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), byteArrayOf(1, 2, 3), listOf(vmesno), nonce)) == CastPravila.Pristnost.VERIGA, "potrdilo naprave ni potrdilo")

    // Googlova korena: odtisa kot v openscreen (preneseno 25. 10. 2026).
    val k = CastPravila.korenine()
    preveri(k.size == 2, "dva korena")
    preveri(odtis(k[0]) == "80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA", "Cast Root CA")
    preveri(odtis(k[1]) == "CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46", "Eureka Root CA")
    // Prava javna vmesna potrdila JBL: MediaTek Cast Audio je podpisal Googlov koren Cast, HARMAN pa MediaTek.
    val mt = CastPravila.potrdilo(b64(JBL_VMESNO_MEDIATEK))!!
    val hm = CastPravila.potrdilo(b64(JBL_VMESNO_HARMAN))!!
    preveri(CastPravila.izdal(k[0], mt) && CastPravila.izdal(mt, hm), "veriga JBL do korena Cast")
    preveri(!CastPravila.izdal(k[1], mt) && !CastPravila.izdal(k[0], hm) && !CastPravila.izdal(hm, mt), "ne drug koren, ne preskok")

    // Stanja, kot jih je 25. 10. 2026 vracal JBL (stara seja ob novem LOAD pride z INTERRUPTED).
    val ms = JSONObject("""{"type":"MEDIA_STATUS","requestId":7,"status":[{"mediaSessionId":1,"playerState":"IDLE","idleReason":"INTERRUPTED","currentTime":0},
        {"mediaSessionId":3,"playbackRate":1,"playerState":"PLAYING","currentTime":12.5,"media":{"contentId":"https://x/a.m4a","contentType":"audio/mp4","duration":213.4}}]}""")
    val z = CastPravila.zadnje(ms)!!
    preveri(z.seja == 3 && z.stanje == "PLAYING" && z.casMs == 12_500L && z.trajanjeMs == 213_400L && z.naslov == "https://x/a.m4a", "zadnja seja $z")
    preveri(CastPravila.vDlna(z) == "PLAYING" && CastPravila.vDlna(null) == "NO_MEDIA_PRESENT" &&
        CastPravila.vDlna(z.copy(stanje = "PAUSED")) == "PAUSED_PLAYBACK" && CastPravila.vDlna(z.copy(stanje = "BUFFERING")) == "TRANSITIONING" &&
        CastPravila.vDlna(z.copy(stanje = "IDLE")) == "STOPPED", "stanja v DLNA")
    val rs = JSONObject("""{"requestId":2,"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845","sessionId":"s1","transportId":"t1"}],
        "volume":{"controlType":"master","level":0.4399999976158142,"muted":false,"stepInterval":0.04}}}""")
    val st = CastPravila.stanjeSprejemnika(rs)!!
    preveri(st.aplikacije.single() == CastPravila.Aplikacija("CC1AD845", "s1", "t1") && CastPravila.glasnost(st.glasnost) == 44, "sprejemnik")
    preveri(CastPravila.stanjeSprejemnika(ms) == null && CastPravila.stanjaMedija(rs).isEmpty() && CastPravila.zadnje(null) == null, "drugo sporocilo")
    preveri(CastPravila.glasnost(-1.0) == -1 && CastPravila.glasnost(1.7) == 100 && CastPravila.glasnost(0.0) == 0, "glasnost")
    val l = JSONObject(CastPravila.nalozi(9, "https://rr1.googlevideo.com/videoplayback?x=1", "audio/mp4", "Naslov", "Izvajalec", "content://slika", true))
    val m = l.getJSONObject("media")
    preveri(l.getString("type") == "LOAD" && l.getInt("requestId") == 9 && l.getBoolean("autoplay") && m.getString("contentType") == "audio/mp4" &&
        m.getString("streamType") == "BUFFERED" && !m.getJSONObject("metadata").has("images"), "LOAD")
    preveri(JSONObject(CastPravila.nalozi(1, "u", "audio/mp4", "a", "b", "https://i/x.jpg", false)).getJSONObject("media")
        .getJSONObject("metadata").getJSONArray("images").length() == 1, "naslovnica https")
    preveri(CastPravila.zavrnitev(JSONObject("""{"type":"LOAD_FAILED"}""")) && !CastPravila.zavrnitev(ms) && !CastPravila.zavrnitev(null), "zavrnitev")

    // Tok, ki potrebuje glave zahteve (kljuc vira API, Referer), ne gre na zvocnik (popravek 25. 10. 2026).
    preveri(ZvocnikPravila.primerna("https://radio.example/a.mp3", false), "navaden tok")
    preveri(!ZvocnikPravila.primerna("https://radio.example/a.mp3", false, "", imaGlave = true), "tok z glavami")
    println("CastPravilaTest: OK")
}
