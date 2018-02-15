package cn.nextours.springboot.wopi.controller

import cn.nextours.springboot.annotation.OpenForSpringAnnotation
import cn.nextours.springboot.wopi.ConflictException
import cn.nextours.springboot.wopi.domain.App
import cn.nextours.springboot.wopi.domain.WopiAccessToken
import cn.nextours.springboot.wopi.domain.WopiCheckFileInfo
import cn.nextours.springboot.wopi.domain.WopiConfiguration
import cn.nextours.springboot.wopi.service.LockService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import java.net.HttpURLConnection
import java.nio.file.Paths
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@OpenForSpringAnnotation
@RequestMapping("/wopi")
@RestController
class WopiController {

    @Autowired
    private var configuration: WopiConfiguration? = null
    @Autowired
    private var lockService: LockService? = null

    @RequestMapping(value = ["/router/{filename:[a-zA-Z0-9.]+}"], method = [RequestMethod.GET])
    fun router(@PathVariable("filename") filename: String,
               @RequestParam(value = "editable", required = false, defaultValue = "false") editable: Boolean,
               req: HttpServletRequest,
               resp: HttpServletResponse) {
        logger.info(">>>>>Router[filename: $filename, editable: $editable]")

        val file = Paths.get(configuration?.docs_dir, filename).toFile()
        if (file == null || !file.exists()) {
            resp.status = HttpURLConnection.HTTP_NOT_FOUND
            return
        }

        val token = WopiAccessToken(editable)

        val app = App.match(file.extension, editable)
        val locationUrl = app.resolveURL(configuration?.owa_url, "${configuration?.check_file_info_url}/$filename", token.toString())

        resp.status = HttpURLConnection.HTTP_MOVED_TEMP
        resp.addHeader("Location", locationUrl)
    }

    @RequestMapping(value = ["/files/{filename:[a-zA-Z0-9.]+}"], method = [RequestMethod.GET])
    fun checkFileInfo(@PathVariable("filename") filename: String,
                      @RequestParam("access_token") access_token: String,
                      req: HttpServletRequest,
                      resp: HttpServletResponse): WopiCheckFileInfo? {
        logger.info(">>>>>CheckFileInfo[filename: $filename, access_token: $access_token]")

        val file = Paths.get(configuration?.docs_dir, filename).toFile()
        if (file == null || !file.exists()) {
            resp.status = HttpURLConnection.HTTP_NOT_FOUND
            return null
        }

        val fileInfo = WopiCheckFileInfo(file.name, "Peng", "Peng", "${file.lastModified()}", file.length())
        logger.info(fileInfo.toString())

        return fileInfo
    }

    @RequestMapping(value = ["/files/{filename:[a-zA-Z0-9.]+}/contents"], method = [RequestMethod.GET])
    fun getFile(@PathVariable("filename") filename: String,
                @RequestParam("access_token") access_token: String,
                req: HttpServletRequest,
                resp: HttpServletResponse) {
        logger.info(">>>>>GetFile[filename: $filename, access_token: $access_token]")

        val file = Paths.get(configuration?.docs_dir, filename).toFile()
        if (file == null) {
            resp.status = HttpURLConnection.HTTP_NOT_FOUND
            return
        }

        val maxExpectedSize = req.getHeader(KEY_X_WOPI_MAX_EXPECTED_SIZE).toLong()
        if (file.length() > maxExpectedSize) {
            resp.status = HttpURLConnection.HTTP_PRECON_FAILED
            return
        }

        with(resp) {
            reset()
            contentType = "application/octet-stream"
            addHeader("Content-Disposition", "attachment;filename=$filename")
            addHeader("Content-Length", "${file.length()}")
            addHeader(KEY_X_WOPI_ITEM_VERSION, "${file.lastModified()}")

            file.forEachBlock { buffer, len ->
                outputStream.write(buffer, 0, len)
            }
            outputStream.flush()
        }
        logger.info("GetFile[version: ${file.lastModified()}]")
    }

    @RequestMapping(value = ["/files/{filename:[a-zA-Z0-9.]+}/contents"], method = [RequestMethod.POST])
    fun putFile(@PathVariable("filename") filename: String,
                @RequestParam("access_token") access_token: String,
                @RequestBody content: ByteArray,
                req: HttpServletRequest,
                resp: HttpServletResponse) {
        logger.info(">>>>>PutFile[filename: $filename, access_token: $access_token, Body Size: ${content.size}]")

        val override = req.getHeader(KEY_X_WOPI_OVERRIDE)
        if (override != X_WOPI_OVERRIDE_PUT) {
            resp.status = HttpURLConnection.HTTP_BAD_REQUEST
            return
        }

        val lock = req.getHeader(KEY_X_WOPI_LOCK)
        val l = lockService?.getLock(filename)
        if (l == null || content.isEmpty()) {
            //must check the current size of the file.
            // If it is 0 bytes, the PutFile request should be considered valid and should proceed.
            // If it is any value other than 0 bytes, or is missing altogether, the host should respond with a 409 Conflict
            with(resp) {
                status = HttpURLConnection.HTTP_CONFLICT
                addHeader(KEY_X_WOPI_LOCK, l?.lock ?: "")
                addHeader(KEY_X_WOPI_LOCK_FAILURE_REASON, "file or content conflict")
            }
            logger.warn("file or content conflict")
        } else if (l.lock == lock) {
            val file = Paths.get(configuration?.docs_dir, filename).toFile()

            with(file) {
                if (!exists()) {
                    createNewFile()
                }
                writeBytes(content)
            }

            with(resp) {
                status = HttpURLConnection.HTTP_OK
                addHeader(KEY_X_WOPI_ITEM_VERSION, "${file.lastModified()}")
            }
        } else {
            with(resp) {
                status = HttpURLConnection.HTTP_CONFLICT
                addHeader(KEY_X_WOPI_LOCK, l.lock)
                addHeader(KEY_X_WOPI_LOCK_FAILURE_REASON, "lock mismatch")
            }
        }
    }

    @RequestMapping(value = ["/files/{filename:[a-zA-Z0-9.]+}"], method = [RequestMethod.POST])
    fun override(@PathVariable("filename") filename: String,
                 @RequestParam("access_token") access_token: String,
                 @RequestHeader(value = KEY_X_WOPI_OVERRIDE, required = true) override: String,
                 @RequestHeader(value = KEY_X_WOPI_LOCK, defaultValue = "") lock: String,
                 @RequestHeader(value = KEY_X_WOPI_OLDLOCK) oldLock: String?,
                 req: HttpServletRequest,
                 resp: HttpServletResponse) {

        if (override != X_WOPI_OVERRIDE_GET_LOCK && lock.isBlank()) {
            resp.status = HttpURLConnection.HTTP_BAD_REQUEST
            return
        }

        val response = when (override) {
            X_WOPI_OVERRIDE_LOCK -> {
                if (oldLock.isNullOrBlank()) {
                    lock(filename, lock)
                } else {
                    unlockAndRelock(filename, lock, oldLock)
                }
            }
            X_WOPI_OVERRIDE_UNLOCK -> {
                unlock(filename, lock)
            }
            X_WOPI_OVERRIDE_GET_LOCK -> {
                getLock(filename)
            }
            X_WOPI_OVERRIDE_REFRESH_LOCK -> {
                refreshLock(filename, lock)
            }
            X_WOPI_OVERRIDE_PUT_RELATIVE -> {
                putRelativeFile(filename)
            }
            else -> {
                logger.warn("Not Supported[override: $override, lock: $lock]")
                LockResponse(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "")
            }
        }

        with(resp) {
            status = response.status
            addHeader(KEY_X_WOPI_LOCK, response.lock)
            if (response.reason != null) {
                addHeader(KEY_X_WOPI_LOCK_FAILURE_REASON, response.reason)
            }
        }
    }

    private fun lock(filename: String,
                     lock: String): LockResponse {
        logger.info(">>>>>Lock[lock: $lock]")

        return try {
            lockService?.lock(filename, lock)
            logger.info("$filename is Locked!")
            LockResponse(HttpURLConnection.HTTP_OK, lock)
        } catch (e: ConflictException) {
            logger.info("$filename lock failed!")
            LockResponse(e.status, e.lock, e.message)
        }
    }

    private fun unlock(filename: String,
                       lock: String): LockResponse {
        logger.info(">>>>>Unlock[lock: $lock]")

        return try {
            lockService?.unlock(filename, lock)
            logger.info("$filename is Unlocked!")
            LockResponse(HttpURLConnection.HTTP_OK, lock)
        } catch (e: ConflictException) {
            logger.info("$filename unlock failed!")
            LockResponse(e.status, e.lock, e.message)
        }
    }

    /**
     * WOPI defines a GetLock operation. However, Office Online does not use it in all cases,
     * even if the host indicates support for the operation using the SupportsGetLock property in CheckFileInfo.
     * Instead, Office Online will sometimes execute lock-related operations on files with missing or
     * known incorrect lock IDs and expects the host to provide the current lock ID in its WOPI response.
     */
    private fun getLock(filename: String): LockResponse {
        logger.info(">>>>>GetLock[filename: $filename]")

        val lock = lockService?.getLock(filename)
        logger.info("$filename's lock is ${lock?.lock ?: "Not Found"}")
        return LockResponse(HttpURLConnection.HTTP_OK, lock?.lock ?: "")
    }

    private fun refreshLock(filename: String,
                            lock: String): LockResponse {
        logger.info(">>>>>RefreshLock[filename: $filename, lock: $lock]")

        return try {
            lockService?.refreshLock(filename, lock)
            logger.info("$filename is refresh lock")
            LockResponse(HttpURLConnection.HTTP_OK, lock)
        } catch (e: ConflictException) {
            logger.info("$filename is refresh lock failed")
            LockResponse(e.status, e.lock, e.message)
        }
    }

    private fun unlockAndRelock(filename: String,
                                lock: String,
                                oldLock: String?): LockResponse {
        logger.info(">>>>>UnlockAndRelock[filename: $filename, lock: $lock, oldLock: $oldLock]")

        return try {
            lockService?.unlockAndRelock(filename, lock, oldLock)
            logger.info("$filename is unlockAndRelock")
            LockResponse(HttpURLConnection.HTTP_OK, lock)
        } catch (e: ConflictException) {
            logger.info("$filename is unlockAndRelock failed")
            LockResponse(e.status, e.lock, e.message)
        }
    }

    private fun putRelativeFile(filename: String): LockResponse {
        logger.info(">>>>>PutRelativeFile[filename: $filename]")
        return LockResponse(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "")
        // TODO disabled
    }

    companion object {
        private const val KEY_X_WOPI_MAX_EXPECTED_SIZE = "X-WOPI-MaxExpectedSize"//GetFile request
        private const val KEY_X_WOPI_ITEM_VERSION = "X-WOPI-ItemVersion"//GetFile response

        private const val KEY_X_WOPI_OVERRIDE = "X-WOPI-Override"
        private const val KEY_X_WOPI_LOCK = "X-WOPI-Lock"
        private const val KEY_X_WOPI_OLDLOCK = "X-WOPI-OldLock"//UnlockAndRelock

        private const val KEY_X_WOPI_LOCK_FAILURE_REASON = "X-WOPI-LockFailureReason"

        private const val X_WOPI_OVERRIDE_LOCK = "LOCK"//Lock、UnlockAndRelock
        private const val X_WOPI_OVERRIDE_UNLOCK = "UNLOCK"//UnLock
        private const val X_WOPI_OVERRIDE_GET_LOCK = "GET_LOCK"//GetLock
        private const val X_WOPI_OVERRIDE_REFRESH_LOCK = "REFRESH_LOCK"//RefreshLock

        private const val X_WOPI_OVERRIDE_PUT = "PUT"//PutFile
        private const val X_WOPI_OVERRIDE_PUT_RELATIVE = "PUT_RELATIVE"//PutRelativeFile

        private val logger: Logger = LoggerFactory.getLogger(WopiController::class.java)

        private fun parsingExtension(filename: String?): String? {
            val lastIndexOf = filename?.lastIndexOf(".") ?: -1
            return if (lastIndexOf < 0) {
                ""
            } else {
                filename?.substring(lastIndexOf)
            }
        }
    }

    private data class LockResponse(val status: Int, val lock: String, val reason: String? = null)
}