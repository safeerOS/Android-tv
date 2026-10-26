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

// Pregled Grok 25 (Z2, Z3): pravi podpis SHA-1 iste naprave; vmesni CA pod vmesnim s pathlen 0 in naprava pod njim; naprava
// brez keyUsage; naprava z neznano kriticno razsiritvijo - vse s kljuci iste izmisljene verige (tools/cast_preizkusna_veriga.sh).
private const val PREIZKUS_TLS_DRUGO = "MIIDHzCCAgegAwIBAgIUZoPGBshV9UVlLE6Z3VsgkciyCAgwDQYJKoZIhvcNAQELBQAwHzEdMBsGA1UEAwwUcHJlaXprdXNuaS10bHMtZHJ1Z2kwHhcNMjYxMDI1MTkyNTM2WhcNMjYxMDI3MTkyNTM2WjAfMR0wGwYDVQQDDBRwcmVpemt1c25pLXRscy1kcnVnaTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKhaFise07aUCW+ysjNtqS61VyIcDTDceRVIC80iHTP3tugaASnbSyjntM+1slD7Mfpw3oxXcjPGGJx0pcJQnHzkhEwNgmBS87FuyC3gdJIwJEDLxLFRA1Ul+q3CAoCrNuq9EcMt4oTUGgiqx3GD0C/L+FzJ0KL072CdNn1livnX1332SRzS+PtlgMrE/71HXlH3QgwHmqpk+2HvE+vtw4+n7FgytuaBBsQaay8Gv75ibeCWy4dgqRuTN+QyN4WnOGn/iCWAzloQQKXjLfQ24PARRfVWjJQv2N543sm6NYITTOb7OZfiRnaW3taxjZNSjq4qYqV35r6gdvCl0kbBQS0CAwEAAaNTMFEwHQYDVR0OBBYEFHqlfWtBv1X6RE+qT6/A/NJGfRKOMB8GA1UdIwQYMBaAFHqlfWtBv1X6RE+qT6/A/NJGfRKOMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBABuqovayeSvzOMvmhL2TNMlsbn0hmedPBl8wHpBuSVahHei8fc8mB7e9Btaiq2+HUedPB8p216NIyFqIgWFCGPXOW+jf+Oj0OPRwgUyvI9mDdSNtqFsWuF2nRlcFBLvJQrAr2gmlB9upNqX2GTMLBDtdbwrolZZ9CbV1IjdXfO02Jka/PNhGyDUtStHgqdMAC2VjWfzILTFUiXcCWDSI6dqa03fiY+xSIbPuOSSrukQRlBKwKarJ2qvIavPH+KPCJkD4jn1nRsEMr3+3+1+bF55npo4uuidEAjMBzzG+sJP7xhlJiJD9Bm6s8ROqzgfoikFtm6px0u3AkRKXyQIXvpI="
private const val PREIZKUS_PODPIS_SHA1 = "X+Qot03mhjOn29zLzxFkrIiBtX1C4SVwN+B/qZGPQzkJRbyUP590O9HzUDHQHbxRWVmZoe3n8td+7VjUH934gmYVGg3t3bEb4yEvYu+PQ/SaHhrDgJun8+WIONyBqJ7cbxDeHBPnQmDxmhiy7gut+2KY1yuBdfHfGVyTW+PTn0Es6opzHPiB4h17y2Jz4w+5DrfV1c+59KgK5rybLQkX5GN+bB+uH55bYqawtqwsKl4IjQBYYOm/xw4OGHlfRWnJOEduYgQMMOjjmgwg1OyLmuWINYmhtZmANF4h6KM/q0MhsuW7t3qtFANDthGnZuyDxDwcmqtCAqHsbfAzsvUWIw=="
private const val PREIZKUS_VMESNO_POD = "MIIDXTCCAkWgAwIBAgIBBTANBgkqhkiG9w0BAQsFADA7MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHzAdBgNVBAMMFlByZWl6a3Vzbm8gdm1lc25vIENhc3QwHhcNMjYxMDI1MTkxODUwWhcNNDUwNjA3MTkxODUwWjBEMRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxKDAmBgNVBAMMH1ByZWl6a3Vzbm8gdm1lc25vIHBvZCBwYXRobGVuIDAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCt+XS18r3KnwjNidw/R5+gtnmcYRX7aXsFTM0/qS8/OP0N0ecgSt68atDWStaeDHtn2qH/BtLjVHTsGaMdHK4/ECyeUUuOBc5oB08z4pz0M9HGJvExg3N37X/BF9UIBICnvR+9J06PzA7eOAwBpGhbMtJvyDg4NxZFIIrC1aROW1icLd6D9cQrOUptcr745GNlE1yXLweqG0M7oZKwMpBgm8Y7Y7runQHa17hmaAbE2sfnxEhRFE/KeWcwsl5OyugefEuVZWLhPh5ZbFOaKOLX6NYAL+VBExBaMY6WHx5UJ8KVTXR8NEnkFQOn5QvwUZbqCfpg0GzrPU6CLK8FCyqdAgMBAAGjYzBhMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMB0GA1UdDgQWBBSq6pw540OmFdjV6byP6FbavDAcOzAfBgNVHSMEGDAWgBQGcfQB6Nxtz+myKmP3k0eyJzP4NTANBgkqhkiG9w0BAQsFAAOCAQEA7vmG3L5nZzFc4xj5eORSsmpEY5Pv3i8ozCctSTBAsAmoRRjPwhbZ8wxu/4WNb//RfFalGXvXB/Cnsr0UnlovnGzMem4w68+Nt87aEfDPFT4lWhLWSF2UV/Rfcru9MlX7LoC+MJVF7Vpp83bXuhKWlSnlXKQSrGSDAPooOm0Fxz5Koq1811+rS0/EHx4IS7vrxsBRwQFdU6aEov+Vidfd15UsGxxTJPIpLyUaj9NQvgE/35iGAFLS/pAa0UXYq+9Go4w3x5s2KWeCTuGqeYNTmzJKpMFrftPyz4XebC40V66riElKkvax6QSEAxaGWNISuK8MZnZCXPayXOj0Xq2Y0g=="
private const val PREIZKUS_NAPRAVA_POD = "MIIDXDCCAkSgAwIBAgIBBjANBgkqhkiG9w0BAQsFADBEMRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxKDAmBgNVBAMMH1ByZWl6a3Vzbm8gdm1lc25vIHBvZCBwYXRobGVuIDAwHhcNMjYxMDI1MTkxODUwWhcNNDUwMjI3MTkxODUwWjAoMSYwJAYDVQQDDB1QcmVpemt1c25hIG5hcHJhdmEgcHJlZ2xvYm9rbzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALF39JIG782eHK/ZoK86hDed9QEXqgZ854XOU7XdFlclx8FNOvDHm41GV5NZCxyYi7QVfShBimM8JGSSfjjGcd3NRvqsrYHWhp52gQzHidaw37q/rM2LAwPiWL1ULbUhFlaesYlY065X/XvfvRDdpceUcvFm9chggF2VpHO7IfDuRGIYzy+e2M4mo0T/8+ydDIpwDmzp0HALR4SBZH+S+xeC7VnNGWVuWPr7oWxJ+Gr4CyGy3Dzp4DGCdP7KSBeJQxb9KvnGppHfwB3o2IZ31b5H5CqRtcCgPBtxeMyUAxNNGrazPSPRnYnseoDOBdk/fu6jjbEJ1OQGj5f4znoqp58CAwEAAaN1MHMwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0lBAwwCgYIKwYBBQUHAwIwHQYDVR0OBBYEFJiIRQgj1a5yvAyhBG/wATGwhnfYMB8GA1UdIwQYMBaAFKrqnDnjQ6YV2NXpvI/oVtq8MBw7MA0GCSqGSIb3DQEBCwUAA4IBAQBy9MFfw0RGximpdYLkYhIZkdPQdzXFv8j+WHOOiAnYvAECm7Q657HQ4jQY+PRfiBQ4mUpDPDhqoSy0QrdIqqQ56X7HqPkPpDJ7p6wRkzeHwp/8kns9UHeqvhJ47bHsVyU3Zypdch4Ib3S0sT8pkx1n8TPiUzaIzQgK2N/kYFD7Ud0DoijsWwEamVlNqldu1Nc2iRCcWhjusYWBYMJe4hp4BYwC0/6uiIRpOKCMe+e+b6RzAUc/n3iNi9ytp5d/QBmC9iOK6h7asILrhiDUQ9Ucajsq+iTXc3Okiv1Wijpl/3KjpANBNn81lflpVKOc3S2m/CLAFBNr5qMOGsBf/38p"
private const val PREIZKUS_PODPIS_POD = "oC293h0Tt64hF3eQ6ZW/fQspVykuQf/ECywEaY4ZRYbTNAEBWUJPVf++I9+EHGRK+PxwkK/hSbWu+9eq7M2aRwHQMb339B+nx1DuGcrXRXefmoG9XayS56tlcJ8WCYbaPtHx0RdYoJo/nss1uipi+IcF30SDWx5pkIt14LZDRPycAqF/G03Vq2dUFtRs31kDCoIKD83rY5GmKYLMtMg4ioeIP4sIHPk6gRKjKsMusmDiE5EirQQkzj/eEMB9Pdr10rNxORPNuyPEdtYcXdL2Szcs1H5WTDuHMw2dThPBpMZw8QFS9dSIxIN89HSEMalV0J36jXz+h4cocS39FUFmrA=="
private const val PREIZKUS_NAPRAVA_BREZ_KU = "MIIDRjCCAi6gAwIBAgIBBzANBgkqhkiG9w0BAQsFADA7MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHzAdBgNVBAMMFlByZWl6a3Vzbm8gdm1lc25vIENhc3QwHhcNMjYxMDI1MTkxODUxWhcNNDUwMjI3MTkxODUxWjArMSkwJwYDVQQDDCBQcmVpemt1c25hIG5hcHJhdmEgYnJleiBrZXlVc2FnZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKGWeESLDeec+8yBSclTceuzc09gDsGjFQlnA/CyvFxfd5kxppaE2uJewK1DEltezEfHd1sq8A2KRztDzNEmRpl7ZcgWMrgxwrlWNR+Lffp72qI1vqpKojZxZ8IwOlwATjaBp99MUQGMG+G4APfsozQXFtuznLfYFj/kow0+PB9fy33UGgXU/mHIT8fIUGmhjh0fuFzEPTqLvszfD1xmvsc7voE+ab5HgP637E82atU8Et18nV7hNcuzrFbnPiFO+7YG7QxVcpym1BEqL1d+F/RQVJKrMOanXXOBtGaY/wXw99FFURWyNqoXAEVjW0Nv1X8TUVnEKh6MYGNyTs3Wo08CAwEAAaNlMGMwDAYDVR0TAQH/BAIwADATBgNVHSUEDDAKBggrBgEFBQcDAjAdBgNVHQ4EFgQUk3YTrmYPY+LrRlYBnYfNKC+Z/HAwHwYDVR0jBBgwFoAUBnH0Aejcbc/psipj95NHsicz+DUwDQYJKoZIhvcNAQELBQADggEBAOB2SsSy1nn9I9hO/zUTfbsaAYsuVDCKlRiworBmI8bCJBgrwuhuTazjL5p/mL2kbtxOUupGI4Jqkac3NiX+TrCuAQAFyYuSJU0jCJgv+wz5C2uENSsa26w16E1EzflKs1YEaaEEVnLk6APuDRKeZBltWwkZzVeZplCjaYnk8BgJd0hEglwLliRq078jNGQLoRdlaif+LuLceuJ7OmS5WSXSZCBGIkreJDUjV0cQvIyjnEGBbr97EgTGGKy3+SNfkz1BsgrD+f7+mFV15gYOo1VfYTlMxZje24mk9ty07GA5xEJvsFAmeQmJ5eaky9SICtuMjXWQxTEODQvec0B3J+k="
private const val PREIZKUS_PODPIS_BREZ_KU = "i5IanRBLViZrZ/c8EpiwhsLgjBqKSG872Z15tT1XZWINFIez+8Oep1O0C77UHtAl68czIGxDsJcuaRlEzltdMpBb0Vawk+bGTSt7fnszTgTUKCCZa9TedZeflASqyQB9cNfYZAewGEOf3DXccZIg+Y24QJtZ8ZA3Ncg3thwa0uFByUMENFZFRAGDTyxV1OBpla85nypgQ0Alu13PMCJ0Q2Hvm/2+tFxS/R1uaENAhd70ODQf/uEAOsk3oUvNakANf2MqjhTErmAwAaSVCT3i0hn4+Olpq0Kq/4hP43KN14APVlea0k+Iitb+h5o5hid8jtm3D1XGKBuueazd4mL5mw=="
private const val PREIZKUS_NAPRAVA_KRITICNA = "MIIDcDCCAligAwIBAgIBCDANBgkqhkiG9w0BAQsFADA7MRgwFgYDVQQKDA9TYWZlZXIgcHJlaXprdXMxHzAdBgNVBAMMFlByZWl6a3Vzbm8gdm1lc25vIENhc3QwHhcNMjYxMDI1MTkxODUyWhcNNDUwMjI3MTkxODUyWjA1MTMwMQYDVQQDDCpQcmVpemt1c25hIG5hcHJhdmEgcyBrcml0aWNubyByYXpzaXJpdHZpam8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDQ+VsI5aa0Wobs849I5p/7X0jRkHU7sHhrN6QHfa1PHgGiQuacvKlOVWFVQq9YrnS7eCF8TeFbXD2cKiQef7fUZrUvAQhJS4T+qYMFAFwJBNMgMLdpR25YURfZNvvB3y5Dl//wzO0hqGND/ljKFcwD3Yj9PLTatHj72eMlqv0N6WVbcA6fKhOfb0a8uPdrJV9BoPA8BIK0ou2cxSK3q1WLfHQFdDJsgBjV7uvcMI9N1BilPsa7AJOjqi01rX58suPFeW05yLBeelD+WZrhOsHrT2XUpTvjXjM6p8YEkjkEL34Oj91LRPl/t/0krJUeNlkRSqOow75Lbe1qHOmp5ytBAgMBAAGjgYQwgYEwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwIQYJKwYBBAGGjR8BAQH/BBEMD3NhZmVlci1wcmVpemt1czAdBgNVHQ4EFgQUVrafq4gSvUjyynAhaLFheQlBTlcwHwYDVR0jBBgwFoAUBnH0Aejcbc/psipj95NHsicz+DUwDQYJKoZIhvcNAQELBQADggEBAGXHTUp3jaF+OFOlbG4VWnZp+QYNqF1x2j9GQgJjqAKi3pjkycD7j8bAWhvX7O5hAGd4Op68qHs+Tdi9mXYWJAgEIg8FjDPW+/JZuMngkzmF7hDAwBj1j+fH3Bap80k59pY4QpSUOglsL3ehE45D9RD/HjgOKA7PKI7dP8tZce3+vyGkMEObwQW7CjejAtpbckeoqI7Y03Xqemln+QBK/ZQu+vsd6fZmNi+DIqD370+hFBN4i7mF0znfc2npCpcNWs69nplVDfV/71NTimLV20UYld8nZ9nus1yn9wfrwoqCUFE+4ZA4IsJNcYe9w0z1jzVoKvx4dQ85dEuRsZuf8Bc="
private const val PREIZKUS_PODPIS_KRITICNA = "dH40zL2JIw5DNnEJ+pNRHDiIaJ8sXpXPvOw73EP3hoyvKX+vow9+815cLpvzq1tH9MRbwEF6jXnVaL9qaF3NMb0etT6QwIJVUZEoVLsJSTpSdOSHXlSAbup9/CexbOgrOJHUePp28sp5gOugGkUp37N+3asx21jFzD4VVr1So0txPwaVVoo+oQtpG9nfhBkEluH4pcIBxKM88+qNyuk682u7QrUvxUkSXEPQPjiA8c29M5i1a0ja665IZxseCWSCIF+I23dm+c/W/TFcPFcjXuNe+elElHHCnZpbTuiwkQCnqWzBqwztCor6stkZnKPLZkEmySyE7g6ryLWwjIk6gA=="

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
    // Ura, ko veljajo tudi potrdila, dodana po prvi verigi (pregled Grok 25, istega dne pozneje).
    val pozneje = CastPravila.potrdilo(tls)!!.notAfter.time - 3_600_000L
    val naprava = b64(PREIZKUS_NAPRAVA)
    val vmesno = b64(PREIZKUS_VMESNO)
    fun preverba(odg: ByteArray, n: ByteArray = nonce, t: ByteArray = tls, ura: Long = zdaj, korenine: List<X509Certificate> = listOf(koren)) =
        CastPravila.odgovorPristnosti(odg)?.let { CastPravila.preveri(it, n, t, ura, korenine) }
    val dobro = odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce)
    preveri(preverba(dobro) == CastPravila.Pristnost.OK, "prava naprava")
    preveri(preverba(dobro, n = "fedcba9876543210".toByteArray()) == CastPravila.Pristnost.NONCE, "odgovor na tuj izziv")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), "x".repeat(16).toByteArray())) == CastPravila.Pristnost.NONCE, "drug nonce v odgovoru")
    // Napadalec med napravo in nami ima drugo potrdilo TLS: podpis naprave zanj ne velja.
    preveri(preverba(dobro, t = b64(PREIZKUS_TLS_DRUGO), ura = pozneje) == CastPravila.Pristnost.PODPIS, "drugo potrdilo TLS")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_BREZ_NONCE), naprava, listOf(vmesno), nonce)) == CastPravila.Pristnost.PODPIS, "podpis brez nonce")
    // Odgovor brez nonce ni vezan na nas izziv: zavrnjen (Grok 25, Z3; JBL nonce vrne - izmerjeno 25. 10. 2026).
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_BREZ_NONCE), naprava, listOf(vmesno), null)) == CastPravila.Pristnost.NONCE, "odgovor brez nonce")
    preveri(preverba(dobro, ura = CastPravila.potrdilo(tls)!!.notAfter.time + 1_000L) == CastPravila.Pristnost.TLS, "potrdilo TLS poteklo")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, emptyList(), nonce)) == CastPravila.Pristnost.VERIGA, "manjka vmesno")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), vmesno, listOf(vmesno), nonce)) == CastPravila.Pristnost.VERIGA, "CA kot naprava")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_CA), b64(PREIZKUS_CA_PODPISNIK), emptyList(), nonce)) == CastPravila.Pristnost.VERIGA, "CA s podpisovanjem kot naprava")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce, algoritem = 2)) == CastPravila.Pristnost.PODPIS, "PSS")
    // Pravi podpis SHA-1 (ne le napacno zgoscevanje) in neznana vrednost zgoscevanja: zavrnjena (Grok 25, Z3).
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_SHA1), naprava, listOf(vmesno), nonce, zgoscevanje = 0)) == CastPravila.Pristnost.PODPIS, "pravi podpis SHA-1")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS), naprava, listOf(vmesno), nonce, zgoscevanje = 7)) == CastPravila.Pristnost.PODPIS, "neznano zgoscevanje")
    // Potrdilo TLS, ki velja vec kot 4 dni naprej (openscreen kMaxSelfSignedCertLifetimeInDays), ni potrdilo sprejemnika Cast.
    preveri(preverba(dobro, t = b64(PREIZKUS_KOREN)) == CastPravila.Pristnost.TLS, "potrdilo TLS velja predolgo")
    // Veriga (Grok 25, Z2).
    preveri(preverba(dobro, ura = pozneje) == CastPravila.Pristnost.OK, "prava naprava pozneje")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_POD), b64(PREIZKUS_NAPRAVA_POD), listOf(b64(PREIZKUS_VMESNO_POD), vmesno), nonce), ura = pozneje) ==
        CastPravila.Pristnost.VERIGA, "CA pod vmesnim s pathlen 0")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_BREZ_KU), b64(PREIZKUS_NAPRAVA_BREZ_KU), listOf(vmesno), nonce), ura = pozneje) ==
        CastPravila.Pristnost.VERIGA, "naprava brez keyUsage")
    preveri(preverba(odgovor(b64(PREIZKUS_PODPIS_KRITICNA), b64(PREIZKUS_NAPRAVA_KRITICNA), listOf(vmesno), nonce), ura = pozneje) ==
        CastPravila.Pristnost.VERIGA, "neznana kriticna razsiritev")

    // Pot po poskusu Cast (lastnik 25. 10. 2026, Grok 25 Z1): nepristna naprava ne dobi nicesar, pristna brez Casta ne gre na DLNA.
    preveri(CastPravila.pot(CastPravila.Poskus.OK, false) == CastPravila.Pot.CAST, "pristna naprava: Cast")
    preveri(CastPravila.pot(CastPravila.Poskus.PREVERBA, false) == CastPravila.Pot.NI_PRISTNA, "preverba pade: ne DLNA")
    preveri(CastPravila.pot(CastPravila.Poskus.PREVERBA, true) == CastPravila.Pot.NI_PRISTNA, "preverba pade pri znani napravi")
    preveri(CastPravila.pot(CastPravila.Poskus.NI_CASTA, true) == CastPravila.Pot.CAST_NEDOSEGLJIV, "znana naprava brez Casta: ne DLNA")
    preveri(CastPravila.pot(CastPravila.Poskus.NI_CASTA, false) == CastPravila.Pot.DLNA, "cisti DLNA ostane DLNA")
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
    // Prenos s te naprave sredi skladbe: LOAD ze na tem mestu (prej od zacetka in preskok - na JBL se je slisal zacetek).
    preveri(JSONObject(CastPravila.nalozi(2, "u", "audio/mp4", "a", "b", "", true, 109.5)).getDouble("currentTime") == 109.5 &&
        l.getDouble("currentTime") == 0.0, "LOAD na mestu")
    preveri(JSONObject(CastPravila.nalozi(1, "u", "audio/mp4", "a", "b", "https://i/x.jpg", false)).getJSONObject("media")
        .getJSONObject("metadata").getJSONArray("images").length() == 1, "naslovnica https")
    preveri(CastPravila.zavrnitev(JSONObject("""{"type":"LOAD_FAILED"}""")) && !CastPravila.zavrnitev(ms) && !CastPravila.zavrnitev(null), "zavrnitev")

    // Tok, ki potrebuje glave zahteve (kljuc vira API, Referer), ne gre na zvocnik (popravek 25. 10. 2026).
    preveri(ZvocnikPravila.primerna("https://radio.example/a.mp3", false), "navaden tok")
    preveri(!ZvocnikPravila.primerna("https://radio.example/a.mp3", false, "", imaGlave = true), "tok z glavami")
    println("CastPravilaTest: OK")
}
