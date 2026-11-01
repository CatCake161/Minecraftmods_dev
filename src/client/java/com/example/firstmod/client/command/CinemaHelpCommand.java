package com.example.firstmod.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.List;

/** In-game command reference. Invoked with {@code /cinema_help} or {@code /cinema help}. */
public final class CinemaHelpCommand {
	private static final List<List<String>> PAGES = List.of(
		List.of(
			"§6Cinema 使用帮助 §7(1/7) §fAV 接收器与播放",
			"§e/cinema_play <媒体库相对路径>§f  选片并播放，例如 movies/demo.mp4",
			"§e/cinema_pause§f  暂停 / 继续；§e/cinema_stop§f  停止",
			"§e/cinema_seek <秒>§f  跳转；§e/cinema_volume <0-1>§f  音量",
			"§e/cinema_mute§f  静音切换；§e/cinema_link§f  重新配对投影仪",
			"§e/cinema_receiver info§f  查看接收器状态",
			"§7命令作用于 64 格内最近的 AV 接收器；默认库 cinema/media/，外部库在接收器 GUI 的 Settings 添加。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(2/7) §f投影仪（客户端）",
			"§e/cinema_projector_client info§f  查看镜头/配对屏幕/灯泡",
			"§e... throw <0.6-4.0>§f  设置投掷比；§e... throw auto§f 自动适配屏幕",
			"§e... focus <0.25-64>§f  手动焦距（米）；§e... focus auto§f 对焦屏幕",
			"§e... keystone <垂直角度> <水平角度>§f  梯形校正，范围 -30..30",
			"§e... brightness <0.01-1.0>§f  亮度；§e... wash <0-1>§f  环境光洗白；§e... power§f  开关灯泡",
			"§7只改最近的客户端投影仪。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(3/7) §f投影仪（服务端/真实方块）",
			"§e/cinema_projector info§f  查看参数",
			"§e/cinema_projector throw <投掷比>§f",
			"§e/cinema_projector focus auto|<米>§f",
			"§e/cinema_projector keystone <垂直角度> <水平角度>§f",
			"§e/cinema_projector brightness <0.01-1.0>§f；§e... wash <0-1>§f  洗白；§e... power§f",
			"§7修改会同步给 LAN 客户端；命令作用于 48 格内最近的投影仪。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(4/7) §f屏幕、投影仪与接收器",
			"§7屏幕由同朝向、同平面的屏幕方块组成，最大 16×16。",
			"§7投影仪会自动寻找前方 48 格内最近的屏幕；屏幕必须朝向投影仪。",
			"§7接收器会自动配对 48 格内最近的投影仪；§e/cinema_link§f 可重新扫描。",
			"§7右键投影仪可打开 GUI，直接调整环境光洗白强度。",
			"§7拆掉投影仪后屏幕保持黑屏；重新放回后等待接收器自动重连，或执行 /cinema_link。",
			"§e/time set midnight§f  测试夜间环境光；§e/time set noon§f  回到正午"
		),
		List.of(
			"§6Cinema 使用帮助 §7(5/7) §f手动 / 自动测试",
			"§7手动模式（不退出、不锁定玩家）：",
			"§f-Dfirstmod.spike.video=<绝对路径>",
			"§f-Dfirstmod.spike.manual=true",
			"§7自动模式：把 manual=true 换成 exit=true。",
			"§7测试环境会固定时间、天气、玩家位置，并关闭动物生成。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(6/7) §f常见问题",
			"§7视频停在最后一帧：/cinema_seek 0 重播。",
			"§7屏幕没画面：确认接收器正在播放、投影仪已配对、屏幕朝向投影仪。",
			"§7同一时间只允许一个接收器占用音频；先 /cinema_stop 再切换。",
			"§7外部媒体库：接收器 GUI → Settings → 输入文件夹绝对路径 → Add library。",
			"§7Media 页左上角 Library 按钮切换默认库与外部库。",
			"§7真实方块用 /cinema_projector；客户端调参用 /cinema_projector_client。",
			"§e/cinema_audio_spike§f  运行 5.1/OpenAL 测试",
			"§7找不到新命令时重启客户端；帮助命令本身不会修改世界。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(7/7) §fFFmpeg 必须安装",
			"§7从 https://www.gyan.dev/ffmpeg/builds/ 下载 full build。",
			"§f<game>/mods/ffmpeg/bin/ffmpeg.exe",
			"§f<game>/mods/ffmpeg/bin/ffprobe.exe",
			"§7也可以放 mods/ffmpeg.exe，或用 -Dfirstmod.ffmpeg.path=...",
			"§7HDR 默认 720p、SDR 默认 1080p：-Dfirstmod.video.maxHeight=0 保留原生。",
			"§7硬件解码默认 auto：-Dfirstmod.ffmpeg.hwaccel=none 可关闭。"
		)
	);

	private CinemaHelpCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_help")
			.executes(context -> show(context.getSource(), 1))
			.then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1, PAGES.size()))
				.executes(context -> show(context.getSource(),
					IntegerArgumentType.getInteger(context, "page")))));
		dispatcher.register(ClientCommandManager.literal("cinema")
			.executes(context -> show(context.getSource(), 1))
			.then(ClientCommandManager.literal("help")
				.executes(context -> show(context.getSource(), 1))
				.then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1, PAGES.size()))
					.executes(context -> show(context.getSource(),
						IntegerArgumentType.getInteger(context, "page"))))));
	}

	private static int show(FabricClientCommandSource source, int page) {
		List<String> lines = PAGES.get(page - 1);
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		if (page < PAGES.size()) {
			source.sendFeedback(Component.literal("§7下一页：/cinema_help " + (page + 1)));
		}
		return 1;
	}
}
