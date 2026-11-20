using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Collections.Concurrent;

var root = AppContext.BaseDirectory;
Console.OutputEncoding = Encoding.UTF8;
var lan = args.Contains("--lan");
var port = int.TryParse(Environment.GetEnvironmentVariable("PHONERESCUE_PORT"), out var configuredPort) ? configuredPort : 18765;
var dataDir = Environment.GetEnvironmentVariable("PHONERESCUE_DATA") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneRescue");
var workspace = Path.Combine(dataDir, "workspace");
Directory.CreateDirectory(Path.Combine(workspace, "inbox"));
Directory.CreateDirectory(Path.Combine(workspace, "outbox"));
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [], ContentRootPath = root, WebRootPath = Path.Combine(root, "wwwroot") });
builder.WebHost.UseUrls($"http://{(lan ? "0.0.0.0" : "127.0.0.1")}:{port}");
builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = 100 * 1024 * 1024);
builder.Logging.ClearProviders();
var app = builder.Build();
var token = lan ? Convert.ToHexString(RandomNumberGenerator.GetBytes(24)) : RandomNumberGenerator.GetInt32(10000).ToString("D4");
var jobs = new ConcurrentDictionary<string, Job>();
var gate = new SemaphoreSlim(1, 1);
app.Lifetime.ApplicationStopping.Register(() => {
    foreach (var job in jobs.Values) job.Cancel.Cancel();
    if (gate.Wait(TimeSpan.FromSeconds(5))) gate.Release();
});
var logDir = Path.Combine(dataDir, "logs");
Directory.CreateDirectory(logDir);
var pairingFile = Path.Combine(Path.GetDirectoryName(logDir)!, "session.json");
app.Lifetime.ApplicationStarted.Register(() => {
    File.WriteAllText(pairingFile, JsonSerializer.Serialize(new { url = $"http://127.0.0.1:{port}/#" + token, code = token, processId = Environment.ProcessId }));
    var connector = Path.Combine(root, "connect-usb.ps1");
    if (!lan && port == 18765 && File.Exists(connector)) {
        var psi = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", connector, "-Watch", "-WaitSeconds", "300" }) psi.ArgumentList.Add(a);
        try { Process.Start(psi); } catch (Exception e) { Console.WriteLine("USB 自动连接未启动：" + e.Message); }
    }
});
app.Lifetime.ApplicationStopped.Register(() => { try { File.Delete(pairingFile); } catch { } });
app.Use(async (ctx, next) => {
    ctx.Response.Headers["Cache-Control"] = "no-store";
    ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
    ctx.Response.Headers["X-Frame-Options"] = "DENY";
    ctx.Response.Headers["Referrer-Policy"] = "no-referrer";
    ctx.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'";
    if (ctx.Request.Path.StartsWithSegments("/api")) {
        var supplied = Encoding.UTF8.GetBytes(ctx.Request.Headers["X-Rescue-Token"].ToString());
        if (!CryptographicOperations.FixedTimeEquals(supplied, Encoding.UTF8.GetBytes(token))) { ctx.Response.StatusCode = 401; return; }
        var origin = ctx.Request.Headers.Origin.ToString();
        if (origin.Length > 0 && origin != $"{ctx.Request.Scheme}://{ctx.Request.Host}") { ctx.Response.StatusCode = 403; return; }
    }
    await next();
});
app.UseDefaultFiles();
app.UseStaticFiles();
app.MapGet("/api/status", () => new { machine = Environment.MachineName, mode = lan ? "LAN" : "USB / 本机", logDir, workspace, inbox = Path.Combine(workspace,"inbox"), outbox = Path.Combine(workspace,"outbox"), maxFileBytes = BridgeFiles.MaxBytes, version = "0.3.0" });
app.MapGet("/api/files", () => Results.Json(BridgeFiles.List(workspace)));
app.MapPost("/api/files/upload", async (HttpContext ctx) => {
    if (ctx.Request.ContentLength > BridgeFiles.MaxBytes) return Results.StatusCode(413);
    var name = ctx.Request.Query["name"].ToString();
    if (string.IsNullOrWhiteSpace(name) || name.Length > 180 || name != Path.GetFileName(name) || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        return Results.BadRequest(new { error = "文件名无效" });
    if (BridgeFiles.Resolve(workspace, "inbox/" + name) == null) return Results.BadRequest(new { error = "上传目录或文件名无效" });
    var folder = Path.Combine(workspace, "inbox", Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(folder);
    var path = Path.Combine(folder, name);
    var temp = path + ".uploading";
    try {
        await using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true)) {
            var buffer = new byte[65536]; long total = 0; int count;
            while ((count = await ctx.Request.Body.ReadAsync(buffer, ctx.RequestAborted)) > 0) {
                total += count; if (total > BridgeFiles.MaxBytes) return Results.StatusCode(413);
                await output.WriteAsync(buffer.AsMemory(0, count), ctx.RequestAborted);
            }
        }
        File.Move(temp, path);
        return Results.Json(BridgeFiles.Describe(workspace, path), statusCode: 201);
    } finally { if (File.Exists(temp)) File.Delete(temp); }
});
app.MapGet("/api/files/download", (string path) => {
    var full = BridgeFiles.Resolve(workspace, path);
    if (full == null || !File.Exists(full)) return Results.NotFound();
    if (new FileInfo(full).Length > BridgeFiles.MaxBytes) return Results.StatusCode(413);
    return Results.File(full, "application/octet-stream", Path.GetFileName(full), enableRangeProcessing: true);
});
app.MapGet("/api/jobs/{id}", (string id) => jobs.TryGetValue(id, out var job) ? Results.Json(job.Snapshot()) : Results.NotFound());
app.MapPost("/api/jobs/{id}/cancel", (string id) => {
    if (!jobs.TryGetValue(id, out var job)) return Results.NotFound();
    job.Cancel.Cancel(); return Results.Ok();
});
app.MapPost("/api/jobs", async (CommandRequest req) => {
    if (!Guid.TryParse(req.Id, out _) || string.IsNullOrWhiteSpace(req.Command) || req.Command.Length > 16000 || req.TimeoutSeconds < 1 || req.TimeoutSeconds > 300)
        return Results.BadRequest(new { error = "需要 UUID、非空命令和 1–300 秒超时。" });
    if (jobs.TryGetValue(req.Id, out var old)) {
        if (old.Request != req) return Results.Conflict(new { error = "请求编号已被其他操作使用。" });
        return Results.Json(old.Snapshot());
    }
    if (!Directory.Exists(req.WorkingDirectory)) return Results.BadRequest(new { error = "工作目录不存在。" });
    if (jobs.Count >= 500) return Results.BadRequest(new { error = "本次会话已达 500 条，请重启程序。" });
    if (!await gate.WaitAsync(0)) return Results.Conflict(new { error = "已有命令运行，请等待或取消。" });
    var job = new Job(req);
    if (!jobs.TryAdd(req.Id, job)) { gate.Release(); return Results.Conflict(); }
    _ = Task.Run(async () => {
        try {
            var script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; $ErrorActionPreference = 'Stop'; try { & {\n" + req.Command + "\n}; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE } } catch { [Console]::Error.WriteLine(($_ | Out-String)); exit 1 }";
            var psi = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe")) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8, WorkingDirectory = req.WorkingDirectory
            };
            foreach (var a in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Convert.ToBase64String(Encoding.Unicode.GetBytes(script)) }) psi.ArgumentList.Add(a);
            using var process = Process.Start(psi) ?? throw new Exception("无法启动 PowerShell");
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(req.TimeoutSeconds));
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(job.Cancel.Token, timeout.Token);
            var stdout = job.Pump(process.StandardOutput, false);
            var stderr = job.Pump(process.StandardError, true);
            var state = "completed";
            try { await process.WaitForExitAsync(linked.Token); }
            catch (OperationCanceledException) {
                state = job.Cancel.IsCancellationRequested ? "cancelled" : "timed_out";
                try { process.Kill(true); } catch { }
                await process.WaitForExitAsync();
            }
            await Task.WhenAll(stdout, stderr).WaitAsync(TimeSpan.FromSeconds(3));
            job.Finish(state, process.ExitCode);
        } catch (Exception e) { job.Append(e.Message, true); job.Finish("failed", null); }
        finally {
            try { await File.WriteAllTextAsync(Path.Combine(logDir, req.Id + ".json"), JsonSerializer.Serialize(new { request = req, result = job.Snapshot() }, new JsonSerializerOptions { WriteIndented = true })); }
            catch (Exception e) { job.Append("日志写入失败：" + e.Message, true); }
            gate.Release();
        }
    });
    return Results.Json(job.Snapshot(), statusCode: 202);
});
Console.WriteLine("PhoneRescue — 当前用户权限；Ctrl+C 退出。关闭程序不会撤销已执行的修改。");
Console.WriteLine(lan ? "局域网配对凭证：" + token : "USB 四位配对码：" + token);
Console.WriteLine($"配对地址：http://127.0.0.1:{port}/#" + token);
Console.WriteLine("手机文件与任务产物：" + workspace);
Console.WriteLine(lan ? "LAN 模式：仅在可信网络使用。HTTP 未加密，优先使用 USB。手机使用电脑局域网 IP 和相同端口、#密码。" : "USB 模式：仅监听本机。Android 执行 adb reverse tcp:18765 tcp:18765 后访问相同地址。");
Console.WriteLine("日志包含命令和输出，保存在：" + logDir);
app.Run();

record CommandRequest(string Id, string Command, string WorkingDirectory, int TimeoutSeconds = 60);
static class BridgeFiles {
    public const long MaxBytes = 100 * 1024 * 1024;
    public static string? Resolve(string root, string relative) {
        if (string.IsNullOrWhiteSpace(relative) || Path.IsPathRooted(relative) || relative.Contains(':')) return null;
        var parts = relative.Replace('\\', '/').Split('/');
        if (parts.Length < 2 || (parts[0] != "inbox" && parts[0] != "outbox") || parts.Any(p => p is "" or "." or ".." || p.EndsWith('.') || p.EndsWith(' '))) return null;
        var current = root;
        foreach (var part in parts) {
            if (part.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return null;
            current = Path.Combine(current, part);
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) return null;
        }
        return current;
    }
    public static object Describe(string root, string path) => new { name = Path.GetFileName(path), path = Path.GetRelativePath(root,path).Replace('\\','/'), fullPath = path, bytes = new FileInfo(path).Length, modifiedUtc = File.GetLastWriteTimeUtc(path) };
    public static object List(string root) {
        var files = new List<object>(); bool truncated = false;
        var options = new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint, IgnoreInaccessible = true };
        foreach (var dir in new[] { "inbox", "outbox" }) {
            var start = Path.Combine(root, dir);
            if ((File.GetAttributes(start) & FileAttributes.ReparsePoint) != 0) continue;
            foreach (var file in Directory.EnumerateFiles(start, "*", options)) {
                if (file.EndsWith(".uploading", StringComparison.OrdinalIgnoreCase)) continue;
                if (files.Count >= 200) { truncated = true; break; }
                try { files.Add(Describe(root,file)); } catch (IOException) { }
            }
        }
        return new { workspace = root, files, truncated, maxFileBytes = MaxBytes };
    }
}
class Job(CommandRequest request) {
    public CommandRequest Request { get; } = request;
    public CancellationTokenSource Cancel { get; } = new();
    readonly object sync = new();
    readonly StringBuilder output = new(), error = new();
    string state = "running";
    int? exitCode;
    bool truncated;
    public void Append(string text, bool isError) { lock(sync) { var b = isError ? error : output; var available = 131072 - b.Length; if (text.Length > available) truncated = true; if (available > 0) b.Append(text.AsSpan(0, Math.Min(available, text.Length))); } }
    public async Task Pump(StreamReader reader, bool isError) { var buffer = new char[2048]; int n; while ((n = await reader.ReadAsync(buffer)) > 0) Append(new string(buffer, 0, n), isError); }
    public void Finish(string value, int? code) { lock(sync) { state = value; exitCode = code; } }
    public object Snapshot() { lock(sync) return new { id = Request.Id, state, exitCode, stdout = output.ToString(), stderr = error.ToString(), truncated }; }
}
