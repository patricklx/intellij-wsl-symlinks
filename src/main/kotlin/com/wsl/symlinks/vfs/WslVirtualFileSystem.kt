package com.wsl.symlinks.vfs

import ai.grazie.utils.WeakHashMap
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileAttributes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl
import com.intellij.openapi.vfs.newvfs.impl.StubVirtualFile
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.util.io.URLUtil
import com.intellij.util.lang.PathClassLoader
import java.io.*
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.absolutePathString


class WslfsFileSystemProvider(val provider: FileSystemProvider): FileSystemProvider() {
    val fs = WslVirtualFileSystem()

    class WslAttrs(val vfile: VirtualFile, attrs: BasicFileAttributes): BasicFileAttributes by attrs {
        override fun isSymbolicLink(): Boolean {
            val provider = WslSymlinksProvider.getWslSymlinksProviders(vfile.getWSLDistribution()!!)
            return provider.isWslSymlink(vfile)
        }
    }

    override fun getScheme(): String {
        return provider.scheme
    }

    override fun newFileSystem(uri: URI?, env: MutableMap<String, *>?): FileSystem {
       return provider.newFileSystem(uri, env)
    }

    override fun getFileSystem(uri: URI?): FileSystem {
        return provider.getFileSystem(uri)
    }

    override fun getPath(uri: URI?): Path {
        return provider.getPath(uri)
    }

    override fun newByteChannel(
        path: Path?,
        options: MutableSet<out OpenOption>?,
        vararg attrs: FileAttribute<*>?
    ): SeekableByteChannel {
        return provider.newByteChannel(path, options, *attrs)
    }

    override fun newDirectoryStream(dir: Path?, filter: DirectoryStream.Filter<in Path>?): DirectoryStream<Path> {
        return provider.newDirectoryStream(dir, filter)
    }

    override fun createDirectory(dir: Path?, vararg attrs: FileAttribute<*>?) {
        return provider.createDirectory(dir, *attrs)
    }

    override fun delete(path: Path?) {
        return provider.delete(path)
    }

    override fun copy(source: Path?, target: Path?, vararg options: CopyOption?) {
        return provider.copy(source, target, *options)
    }

    override fun move(source: Path?, target: Path?, vararg options: CopyOption?) {
        return provider.move(source, target, *options)
    }

    override fun isSameFile(path: Path?, path2: Path?): Boolean {
        return provider.isSameFile(path, path2)
    }

    override fun isHidden(path: Path?): Boolean {
        return provider.isHidden(path)
    }

    override fun getFileStore(path: Path?): FileStore {
        return provider.getFileStore(path)
    }

    override fun checkAccess(path: Path?, vararg modes: AccessMode?) {
        return provider.checkAccess(path, *modes)
    }

    override fun <V : FileAttributeView?> getFileAttributeView(
        path: Path?,
        type: Class<V>?,
        vararg options: LinkOption?
    ): V {
        return provider.getFileAttributeView(path, type, *options)
    }

    override fun <A : BasicFileAttributes?> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption?
    ): A {
        val attrs = provider.readAttributes(path, type, *options)
        val vfile = FakeVirtualFile(path.absolutePathString(), null, fs)
        if (vfile.isFromWSL()) {
            return WslAttrs(vfile, attrs as BasicFileAttributes) as A
        }
        return attrs
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): MutableMap<String, Any> {
        val attrs = provider.readAttributes(path, attributes, *options)
        val vfile = FakeVirtualFile(path.absolutePathString(), null, fs)
        if (vfile.isFromWSL()) {

        }
        return attrs
    }

    override fun setAttribute(path: Path?, attribute: String?, value: Any?, vararg options: LinkOption?) {
        return provider.setAttribute(path, attribute, value, *options)
    }
}


class StartupListener: AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        System.setProperty("java.nio.file.spi.DefaultFileSystemProvider", "com.wsl.symlinks.vfs.WslfsFileSystemProvider");
        val point = VirtualFileSystem.EP_NAME.point
        val extension = point.extensionList.find { it.instance is LocalFileSystemImpl && it.instance.javaClass.name.contains("LocalFileSystemImpl") }
        point.unregisterExtension(extension)
    }
}

class FakeVirtualFile(val resPath: String, val vfile: VirtualFile?, val fs: WslVirtualFileSystem): StubVirtualFile(fs) {
    override fun getPath(): String {
        return resPath
    }

    override fun getLength(): Long {
        return vfile?.let { fs.getAttributes(it) }?.length ?: 0
    }

    override fun getParent(): VirtualFile? {
        return vfile?.parent
    }
}

fun <T>Boolean.ifTrue(block: () -> T): T? {
    if (this) {
        return block.invoke()
    }
    return null
}

fun <T>Boolean.ifFalse(block: () -> T): T? {
    if (!this) {
        return block.invoke()
    }
    return null
}

fun <T>Boolean.ifFalse(value: T): T? {
    if (!this) {
        return value
    }
    return null
}



class WslSymlinksProvider(distro: String) {
    val LOGGER = Logger.getInstance(WslSymlinksProvider::class.java)

    private var process: Process? = null
    private var processReader: BufferedReader? = null
    private var processWriter: BufferedWriter? = null
    private val queue: LinkedBlockingQueue<AsyncValue> = LinkedBlockingQueue()
    private val mapped: ConcurrentHashMap<String, AsyncValue> = ConcurrentHashMap()
    val myResourceLock = ReentrantLock()

    class AsyncValue(private val lock: ReentrantLock) {
        public val id = this.hashCode().toString()
        public var request: String? = null
        internal var value: String? = null
        internal val condition = lock.newCondition()
        fun getValue(): String? {
            var elapsed = 0
            while (value == null && elapsed < 1000) {
                if (lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    condition.await(10, TimeUnit.MILLISECONDS)
                    lock.unlock()
                }
                elapsed += 10
            }
//            if (this.value == null) {
//                throw Error("failed to obtain file info for $request")
//            }
            return this.value
        }
    }

    init {
        LOGGER.info("starting WslSymlinksProvider for distro: $distro")

        fun setupProcess() {
            val bash = {}.javaClass.getResource("/files.sh")?.readText()!!
            val location = "\\\\wsl.localhost\\$distro\\var\\tmp\\intellij-idea-wsl-symlinks.sh"
            File(location).writeText(bash)
            val builder = ProcessBuilder("wsl.exe", "-d", distro,  "-e", "bash", "//var/tmp/intellij-idea-wsl-symlinks.sh")
            val process = builder.start()
            this.process = process
            this.processReader = BufferedReader(InputStreamReader(process.inputStream))
            this.processWriter = BufferedWriter(OutputStreamWriter(process.outputStream));
            LOGGER.info("starting process")
            process.onExit().whenComplete { t, u ->
                LOGGER.error("process did exit ${u?.message ?: "no-reason"}")
                setupProcess()
            }
        }

        thread {
            setupProcess()
        }


        thread {
            while (true) {
                try {
                    if (this.processReader == null) {
                        Thread.sleep(100)
                    }
                    val line = this.processReader!!.readLine()
                    val (id, answer) = line.split(";")
                    val a = mapped[id]!!
                    a.value = answer
                    myResourceLock.withLock {
                        a.condition.signalAll()
                    }
                    mapped.remove(id)
                } catch (e: Exception) {
                    LOGGER.error("failed to read", e)
                }
            }
        }

        thread {
            while (true) {
                try {
                    if (this.processWriter == null) {
                        Thread.sleep(100)
                    }
                    val a = this.queue.take()
                    if (a.value != null) continue
                    mapped[a.id] = a
                    this.processWriter!!.write(a.request!!)
                    this.processWriter!!.flush()
                } catch (e: Exception) {
                    LOGGER.error("failed to write", e)
                }
            }
        }
    }

    fun getWSLCanonicalPath(file: VirtualFile): String? {
        if (!file.isFromWSL()) {
            return null
        }

        if (file.cachedWSLCanonicalPath == null) {
            try {
                val wslPath = file.getWSLPath()
                val a = AsyncValue(myResourceLock)
                a.request = "${a.id};read-symlink;${wslPath}\n"
                this.queue.add(a)
                var n = 0
                while (a.getValue() == null && n < 3) {
                    if (!queue.contains(a)) {
                        this.queue.add(a)
                    }
                    n += 1
                }
                if (a.getValue() == null) {
                    return file.path
                }
                val link = a.getValue()
                file.cachedWSLCanonicalPath = file.path.split("/").subList(0, 4).joinToString("/") + link
            } catch (e: IOException) {
                LOGGER.error("failed to getWSLCanonicalPath", e)
            }
        }

        return file.cachedWSLCanonicalPath
    }

    fun isWslSymlink(file: VirtualFile): Boolean {
        if (file.isSymlink != null) {
            return file.isSymlink!!
        }
        if (file.isFromWSL() && file.parent != null) {
            try {
                val path: String = file.path.replace("^//wsl\\$/[^/]+".toRegex(), "").replace("""^//wsl.localhost/[^/]+""".toRegex(), "")
                val a = AsyncValue(myResourceLock)
                a.request = "${a.id};is-symlink;${path}\n"
                this.queue.add(a)
                var n = 0
                while (a.getValue() == null && n < 3) {
                    if (!queue.contains(a)) {
                        this.queue.add(a)
                    }
                    n += 1
                }
                if (a.getValue() == null) {
                    return false
                }
                val isSymLink = a.getValue()
                file.isSymlink = isSymLink.equals("true")
                return isSymLink.equals("true")
            } catch (e: Exception) {
                LOGGER.error("failed isWslSymlink", e)
                return false
            }
        }
        return false
    }

    companion object {
        private var wslSymlinksProviders: MutableMap<String, WslSymlinksProvider> = HashMap()
        fun getWslSymlinksProviders(distro: String): WslSymlinksProvider {
            if (!this.wslSymlinksProviders.containsKey(distro)) {
                this.wslSymlinksProviders[distro] = WslSymlinksProvider(distro)
            }
            return this.wslSymlinksProviders[distro]!!
        }
    }
}

class WslVirtualFileSystem: LocalFileSystemImpl() {
    val LOGGER = Logger.getInstance(WslVirtualFileSystem::class.java)

    init {
        System.setProperty("java.nio.file.spi.DefaultFileSystemProvider", "sun.nio.fs.WindowsFileSystemProvider,com.wsl.symlinks.vfs.WslfsFileSystemProvider");
    }

    override fun getProtocol(): String {
        return "file"
    }

    fun getWslSymlinksProviders(file: VirtualFile): WslSymlinksProvider {
        val distro = file.getWSLDistribution()!!
        return WslSymlinksProvider.getWslSymlinksProviders(distro)
    }

    fun getRealPath(file: VirtualFile): String {
        val symlkinkWsl = file.parentsWithSelf.find { it.isFromWSL() && this.getWslSymlinksProviders(file).isWslSymlink(it) }
        val relative = symlkinkWsl?.path?.let { file.path.replace(it, "") }
        return symlkinkWsl?.let { virtualFile -> this.resolveSymLink(virtualFile) + relative } ?: file.path
    }

    fun getFakeVirtualFile(file: VirtualFile): VirtualFile {
        val filePath = getRealPath(file.parent)
        return FakeVirtualFile(filePath + "/" + file.name, file, this)
    }

    fun getRealVirtualFile(file: VirtualFile): VirtualFile {
        val symlkinkWsl = file.parents.find { it.isFromWSL() && this.getWslSymlinksProviders(file).isWslSymlink(it) }
        val relative = symlkinkWsl?.path?.let { file.path.replace(it, "") }
        val resolved =
            symlkinkWsl?.let { virtualFile -> this.resolveSymLink(virtualFile)?.let { this.findFileByPath(it) } }
        val r = relative?.let { resolved?.findFileByRelativePath(it) } ?: file
        return r
    }

    override fun getInputStream(vfile: VirtualFile): InputStream {
        val file = this.getFakeVirtualFile(vfile)
        return super.getInputStream(file)
    }

    override fun contentsToByteArray(vfile: VirtualFile): ByteArray {
        val file = this.getFakeVirtualFile(vfile)
        return super.contentsToByteArray(file)
    }

    override fun getOutputStream(vfile: VirtualFile, requestor: Any?, modStamp: Long, timeStamp: Long): OutputStream {
        val file = this.getFakeVirtualFile(vfile)
        return super.getOutputStream(file, requestor, modStamp, timeStamp)
    }

    override fun getAttributes(vfile: VirtualFile): FileAttributes? {
        var attributes = super.getAttributes(vfile)

        if (vfile.isFromWSL() && vfile.parent != null) {
            val filePath = getRealPath(vfile.parent)
            val file = FakeVirtualFile(filePath + "/" + vfile.name, vfile, this)
            val isSymlink = this.getWslSymlinksProviders(file).isWslSymlink(file)
            val resolved = isSymlink.ifTrue { this.resolveSymLink(file)?.let { resPath ->
                return@let FakeVirtualFile(resPath, vfile, this)
            } }
            if (!isSymlink && file.path != vfile.path) {
                attributes = super.getAttributes(file)
            }
            if (resolved != null) {
                val resolvedAttrs = super.getAttributes(resolved) ?: attributes ?: return null
                attributes = FileAttributes(resolvedAttrs.isDirectory ?: false, false, true, resolvedAttrs.isHidden, resolvedAttrs.length, resolvedAttrs.lastModified, resolvedAttrs.isWritable, FileAttributes.CaseSensitivity.SENSITIVE)
            }
        }
        return attributes
    }

    override fun resolveSymLink(file: VirtualFile): String? {
        if (file.isFromWSL()) {
            return this.getWslSymlinksProviders(file).getWSLCanonicalPath(file)
        }
        return super.resolveSymLink(file)
    }
    companion object {
        const val PROTOCOL = "\\wsl.localhost"

        fun getInstance() = service<VirtualFileManager>().getFileSystem(PROTOCOL) as WslVirtualFileSystem
    }

    override fun list(vfile: VirtualFile): Array<String> {
        if (vfile.isFromWSL()) {
            val file = FakeVirtualFile(getRealPath(vfile), vfile, this)
            return super.list(file)
        }
        return super.list(vfile)
    }
}

val weakMap = WeakHashMap<VirtualFile, String?>()
val weakMapSymlink = WeakHashMap<VirtualFile, Boolean?>()

private var VirtualFile.cachedWSLCanonicalPath: String?
    get() {
        return weakMap[this]
    }
    set(value) {
        weakMap[this] = value
    }

private var VirtualFile.isSymlink: Boolean?
    get() {
        return weakMapSymlink[this]
    }
    set(value) {
        weakMapSymlink[this] = value
    }


private fun VirtualFile.isFromWSL(): Boolean {
    return this.path.startsWith("//wsl$/") || path.startsWith("//wsl.localhost");
}

private fun VirtualFile.getWSLDistribution(): String? {
    if (!isFromWSL()) {
        return null;
    }

    return path.replace("""^//wsl\$/""".toRegex(), "")
            .replace("""^//wsl.localhost/""".toRegex(), "").split("/")[0]

}

private fun VirtualFile.getWSLPath(): String {
    return path.replace("""^//wsl\$/[^/]+""".toRegex(), "")
            .replace("""^//wsl.localhost/[^/]+""".toRegex(), "")
}

public val VirtualFileUrl.getVirtualFile: VirtualFile?
    get() {
        if (url.startsWith(WslVirtualFileSystem.PROTOCOL)) {
            val protocolSepIndex = url.indexOf(URLUtil.SCHEME_SEPARATOR)
            val fileSystem: VirtualFileSystem? = if (protocolSepIndex < 0) null else WslVirtualFileSystem.getInstance()
            if (fileSystem == null) return null
            val path = url.substring(protocolSepIndex + URLUtil.SCHEME_SEPARATOR.length)
            return fileSystem.findFileByPath(path)
        }
        return null
    }


val VirtualFile.parents: Iterable<VirtualFile>
    get() = object : Iterable<VirtualFile> {
        override fun iterator(): Iterator<VirtualFile> {
            var file = this@parents

            return object : Iterator<VirtualFile> {
                override fun hasNext() = file.parent != null
                override fun next(): VirtualFile {
                    file = file.parent
                    return file
                }
            }
        }
    }

val VirtualFile.parentsWithSelf: Iterable<VirtualFile>
    get() = object : Iterable<VirtualFile> {
        override fun iterator(): Iterator<VirtualFile> {
            var file: VirtualFile? = this@parentsWithSelf

            return object : Iterator<VirtualFile> {
                override fun hasNext() = file != null
                override fun next(): VirtualFile {
                    val f = file
                    file = file?.parent
                    return f!!
                }
            }
        }
    }