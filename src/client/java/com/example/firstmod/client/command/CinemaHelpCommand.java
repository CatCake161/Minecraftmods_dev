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
			"§6Cinema 使用帮助 §7(1/7) §f播放控制",
			"§e/cinema_play <本地文件绝对路径>§f  播放视频",
			"§e/cinema_pause§f  暂停 / 继续",
			"§e/cinema_seek <秒>§f  跳转（关键帧附近重启解码器）",
			"§e/cinema_stop§f  停止播放并释放视频纹理",
			"§7翻页：/cinema_help <页码>，或 /cinema help <页码>"
		),
		List.of(
			"§6Cinema 使用帮助 §7(2/7) §f投影仪（客户端测试）",
			"§e/cinema_projector_client info§f  查看镜头/配对屏幕/灯泡",
			"§e... throw <0.6-4.0>§f  设置投掷比；§e... throw auto§f 自动适配屏幕",
			"§e... focus <0.25-64>§f  手动焦距（米）；§e... focus auto§f 对焦屏幕",
			"§e... keystone <垂直角度> <水平角度>§f  梯形校正，范围 -30..30",
			"§e... brightness <0.05-2.0>§f  亮度；>1 为高亮过驱；§e... power§f  开关灯泡",
			"§7只改最近的客户端投影仪，适合 /cinema_test_scene 搭出来的场景。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(3/7) §f投影仪（服务端/真实方块）",
			"§e/cinema_projector info§f  查看参数",
			"§e/cinema_projector throw <投掷比>§f",
			"§e/cinema_projector focus auto|<米>§f",
			"§e/cinema_projector keystone <垂直角度> <水平角度>§f",
			"§e/cinema_projector brightness <0.05-2.0>§f；>1 为高亮过驱；§e... power§f",
			"§7修改会同步给 LAN 客户端；命令作用于 48 格内最近的投影仪。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(4/7) §f测试场景",
			"§e/cinema_test_scene§f  在当前视角前方搭 4×3 屏幕 + 后方投影仪",
			"§e/cinema_test_scene clear§f  删除本局测试场景、投影仪和玻璃遮挡物",
			"§e/time set midnight§f  测试夜间环境光洗白",
			"§e/time set noon§f  回到正午",
			"§e/gamerule doDaylightCycle true§f  恢复自动时间（测试环境默认关闭）"
		),
		List.of(
			"§6Cinema 使用帮助 §7(5/7) §f手动 / 自动测试",
			"§7手动模式（不截图、不退出、不锁定玩家）：",
			"§f-Dfirstmod.spike.video=<绝对路径>",
			"§f-Dfirstmod.spike.blocks=true -Dfirstmod.spike.manual=true",
			"§7自动模式：把 manual=true 换成 exit=true，截图后退出。",
			"§7投影仪测试附加属性：-Dfirstmod.spike.projector.shadow=true",
			"§7测试环境固定出生点朝南、正午、晴天、无动物。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(6/7) §f常见问题 / 旧测试命令",
			"§7视频停在最后一帧：/cinema_seek 0 重播。",
			"§7屏幕没画面：确认屏幕正面朝向投影仪、48 格内、光路无遮挡。",
			"§7真实方块用 /cinema_projector；测试场景用 /cinema_projector_client。",
			"§e/cinema_visual_spike on|off|screenshot§f  独立的世界空间测试画面",
			"§7注意：visual_spike 不依赖屏幕/投影仪，测方块投影时请先 /cinema_visual_spike off。",
			"§e/cinema_audio_spike§f  运行 5.1/OpenAL 测试",
			"§7找不到新命令时重启客户端；帮助命令本身不会修改世界。"
		),
		List.of(
			"§6Cinema 使用帮助 §7(7/7) §fFFmpeg 必须安装",
			"§7从 https://www.gyan.dev/ffmpeg/builds/ 下载 full build。",
			"§f<game>/mods/ffmpeg/bin/ffmpeg.exe",
			"§f<game>/mods/ffmpeg/bin/ffprobe.exe",
			"§7也可以放 mods/ffmpeg.exe，或用 -Dfirstmod.ffmpeg.path=...",
			"§74K 默认降到 1080p：-Dfirstmod.video.maxHeight=0 保留原生分辨率。",
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
