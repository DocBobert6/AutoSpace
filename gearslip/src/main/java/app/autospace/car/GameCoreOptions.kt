package app.autospace.car

import android.content.Context
import app.autospace.AppSettings

/**
 * r92: per-core options, ported from Mariner's CoreOptions for the car
 * screen: the same option keys, labels, values, and defaults the cores
 * themselves publish, so the playback engine can answer GET_VARIABLE
 * from exactly this map. Values are stored per console and applied
 * when the playback engine lands; editing them here only persists.
 * The Game Boy and DS lists are not ported: those consoles are not
 * in this app's set.
 */
data class CoreOption(
    val key: String,
    val label: String,
    val values: List<String>,
    val labels: Map<String, String> = emptyMap(),
    val default: String
) {
    fun display(value: String): String = labels[value] ?: value
}

object GameCoreOptions {
    private fun range(a: Int, b: Int, step: Int): List<String> =
        (a..b step step).map { it.toString() }

    private fun opt(
        key: String,
        label: String,
        values: List<String>,
        default: String,
        labels: Map<String, String> = emptyMap()
    ) = CoreOption(key, label, values, labels, default)

    private val onOff = mapOf("true" to "On", "false" to "Off")

    private fun boolOpt(key: String, label: String, default: String) =
        opt(key, label, listOf("true", "false"), default, onOff)

    private val fceumm = listOf(
        opt(
            "fceumm_region", "Region",
            listOf("Auto", "NTSC", "PAL", "Dendy"), "Auto"
        ),
        opt(
            "fceumm_game_genie", "Game Genie Add-On",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "fceumm_aspect", "Aspect Ratio",
            listOf("8:7 PAR", "4:3", "PP"), "8:7 PAR",
            mapOf("PP" to "Pixel Perfect")
        ),
        opt(
            "fceumm_overscan_h_left", "Crop Horizontal Left Overscan",
            listOf("0", "4", "8", "12", "16"), "0"
        ),
        opt(
            "fceumm_overscan_h_right", "Crop Horizontal Right Overscan",
            listOf("0", "4", "8", "12", "16"), "0"
        ),
        opt(
            "fceumm_overscan_v_top", "Crop Vertical Top Overscan",
            listOf("0", "4", "8", "12", "16", "20", "24"), "8"
        ),
        opt(
            "fceumm_overscan_v_bottom", "Crop Vertical Bottom Overscan",
            listOf("0", "4", "8", "12", "16", "20", "24"), "8"
        ),
        opt(
            "fceumm_palette", "Color Palette",
            listOf(
                "default", "asqrealc", "restored-wii-vc", "wii-vc",
                "rgb", "yuv-v3", "unsaturated-final",
                "sony-cxa2025as-us", "pal", "bmf-final2",
                "bmf-final3", "smooth-fbx", "composite-direct-fbx",
                "pvm-style-d93-fbx", "ntsc-hardware-fbx",
                "nes-classic-fbx-fs", "nescap", "wavebeam",
                "digital-prime-fbx", "magnum-fbx", "smooth-v2-fbx",
                "nes-classic-fbx", "royaltea", "mugicha", "raw",
                "custom"
            ),
            "default",
            mapOf(
                "default" to "Default",
                "asqrealc" to "AspiringSquire's Real",
                "restored-wii-vc" to "Restored Wii VC",
                "wii-vc" to "Wii Virtual Console",
                "rgb" to "Nintendo RGB PPU",
                "yuv-v3" to "FBX's YUV-V3",
                "unsaturated-final" to "FBX's Unsaturated-Final",
                "sony-cxa2025as-us" to "Sony CXA2025AS US",
                "pal" to "PAL",
                "bmf-final2" to "BMF's Final 2",
                "bmf-final3" to "BMF's Final 3",
                "smooth-fbx" to "FBX's Smooth",
                "composite-direct-fbx" to "FBX's Composite Direct",
                "pvm-style-d93-fbx" to "FBX's PVM Style D93",
                "ntsc-hardware-fbx" to "FBX's NTSC Hardware",
                "nes-classic-fbx-fs" to "FBX's NES Classic (fixed)",
                "nescap" to "RGBSource's NESCAP",
                "wavebeam" to "nakedarthur's Wavebeam",
                "digital-prime-fbx" to "FBX's Digital Prime",
                "magnum-fbx" to "FBX's Magnum",
                "smooth-v2-fbx" to "FBX's Smooth V2",
                "nes-classic-fbx" to "FBX's NES Classic",
                "royaltea" to "Royaltea (PVM-2530)",
                "mugicha" to "Mugicha",
                "raw" to "Raw",
                "custom" to "Custom"
            )
        ),
        opt(
            "fceumm_ntsc_filter", "NTSC Filter",
            listOf(
                "disabled", "composite", "svideo", "rgb", "monochrome"
            ),
            "disabled",
            mapOf("svideo" to "S-Video")
        ),
        opt(
            "fceumm_sndrate_hint", "Sound Samplerate (Hint)",
            listOf("Auto", "32KHz", "44KHz", "48KHz", "96KHz"), "Auto"
        ),
        opt(
            "fceumm_sndquality", "Sound Quality",
            listOf("Low", "High", "Very High"), "Low"
        ),
        opt(
            "fceumm_sndlowpass", "Audio RF Filter",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "fceumm_sndvolume", "Master Volume",
            range(0, 10, 1), "7"
        ),
        opt(
            "fceumm_apu_1", "Audio Channel 1 (Square 1)",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "fceumm_apu_2", "Audio Channel 2 (Square 2)",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "fceumm_apu_3", "Audio Channel 3 (Triangle)",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "fceumm_apu_4", "Audio Channel 4 (Noise)",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "fceumm_apu_5", "Audio Channel 5 (PCM)",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "fceumm_turbo_enable", "Turbo Enable",
            listOf("None", "Player 1", "Player 2", "Both"), "None"
        ),
        opt(
            "fceumm_turbo_delay", "Turbo Delay (in frames)",
            listOf("1", "2", "3", "5", "10", "15", "30", "60"), "3"
        ),
        opt(
            "fceumm_up_down_allowed", "Allow Opposing Directions",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "fceumm_nospritelimit", "No Sprite Limit",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "fceumm_overclocking", "Overclock",
            listOf("disabled", "2x-Postrender", "2x-VBlank"), "disabled"
        ),
        opt(
            "fceumm_ramstate", "RAM Power-On Fill",
            listOf("fill \$ff", "fill \$00", "random"), "fill \$ff",
            mapOf("fill \$ff" to "\$FF", "fill \$00" to "\$00")
        )
    )


    private val snes9x = listOf(
        opt(
            "snes9x_region", "Console Region",
            listOf("auto", "ntsc", "pal"), "auto",
            mapOf("auto" to "Auto", "ntsc" to "NTSC", "pal" to "PAL")
        ),
        opt(
            "snes9x_aspect", "Preferred Aspect Ratio",
            listOf("4:3", "4:3 scaled", "uncorrected", "auto", "ntsc", "pal"),
            "4:3",
            mapOf(
                "4:3 scaled" to "4:3 (Preserved)",
                "uncorrected" to "Uncorrected",
                "auto" to "Auto", "ntsc" to "NTSC", "pal" to "PAL"
            )
        ),
        opt(
            "snes9x_overscan", "Crop Overscan",
            listOf("enabled", "disabled", "auto"), "enabled",
            mapOf("auto" to "Auto")
        ),
        opt(
            "snes9x_gfx_hires", "Enable Hi-Res Mode",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_hires_blend", "Hi-Res Blending",
            listOf("disabled", "merge", "blur"), "disabled",
            mapOf("merge" to "Merge", "blur" to "Blur")
        ),
        opt(
            "snes9x_blargg", "Blargg NTSC Filter",
            listOf(
                "disabled", "monochrome", "rf", "composite",
                "s-video", "rgb"
            ),
            "disabled",
            mapOf("s-video" to "S-Video")
        ),
        opt(
            "snes9x_audio_interpolation", "Audio Interpolation",
            listOf("gaussian", "cubic", "sinc", "none", "linear"),
            "gaussian",
            mapOf(
                "gaussian" to "Gaussian", "cubic" to "Cubic",
                "sinc" to "Sinc", "none" to "None",
                "linear" to "Linear"
            )
        ),
        opt(
            "snes9x_up_down_allowed", "Allow Opposing Directions",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "snes9x_overclock_superfx", "SuperFX Overclocking",
            listOf(
                "50%", "60%", "70%", "80%", "90%", "100%",
                "150%", "200%", "250%", "300%", "350%", "400%",
                "450%", "500%"
            ),
            "100%"
        ),
        opt(
            "snes9x_overclock_cycles", "Reduce Slowdown (Hack, Unsafe)",
            listOf("disabled", "light", "compatible", "max"), "disabled",
            mapOf(
                "light" to "Light", "compatible" to "Compatible",
                "max" to "Max"
            )
        ),
        opt(
            "snes9x_reduce_sprite_flicker",
            "Reduce Flickering (Hack, Unsafe)",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "snes9x_randomize_memory", "Randomize Memory (Unsafe)",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "snes9x_block_invalid_vram_access",
            "Block Invalid VRAM Access",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_echo_buffer_hack", "Echo Buffer Hack (Unsafe)",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "snes9x_layer_1", "Show Layer 1",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_layer_2", "Show Layer 2",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_layer_3", "Show Layer 3",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_layer_4", "Show Layer 4",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_layer_5", "Show Sprite Layer",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_gfx_clip", "Enable Graphic Clip Windows",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_gfx_transp", "Enable Transparency Effects",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_1", "Enable Sound Channel 1",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_2", "Enable Sound Channel 2",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_3", "Enable Sound Channel 3",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_4", "Enable Sound Channel 4",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_5", "Enable Sound Channel 5",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_6", "Enable Sound Channel 6",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_7", "Enable Sound Channel 7",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "snes9x_sndchan_8", "Enable Sound Channel 8",
            listOf("enabled", "disabled"), "enabled"
        )
    )


    private val genesisPlusGx = listOf(
        opt(
            "genesis_plus_gx_system_hw", "System Hardware",
            listOf(
                "auto", "sg-1000", "sg-1000 II",
                "sg-1000 II + ram ext.", "mark-III", "master system",
                "master system II", "game gear",
                "mega drive / genesis"
            ),
            "auto",
            mapOf(
                "auto" to "Auto",
                "sg-1000 II + ram ext." to "SG-1000 II + RAM Ext.",
                "mark-III" to "Mark III",
                "master system" to "Master System",
                "master system II" to "Master System II",
                "game gear" to "Game Gear",
                "mega drive / genesis" to "Mega Drive/Genesis"
            )
        ),
        opt(
            "genesis_plus_gx_region_detect", "System Region",
            listOf("auto", "ntsc-u", "pal", "ntsc-j"), "auto",
            mapOf(
                "auto" to "Auto", "ntsc-u" to "NTSC-U",
                "pal" to "PAL", "ntsc-j" to "NTSC-J"
            )
        ),
        opt(
            "genesis_plus_gx_vdp_mode", "Force VDP Mode",
            listOf("auto", "60hz", "50hz"), "auto",
            mapOf(
                "auto" to "Disabled", "60hz" to "NTSC (60Hz)",
                "50hz" to "PAL (50Hz)"
            )
        ),
        opt(
            "genesis_plus_gx_bios", "System Boot ROM",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_lock_on", "Cartridge Lock-On",
            listOf(
                "disabled", "game genie", "action replay (pro)",
                "sonic & knuckles"
            ),
            "disabled",
            mapOf(
                "game genie" to "Game Genie",
                "action replay (pro)" to "Action Replay (Pro)",
                "sonic & knuckles" to "Sonic & Knuckles"
            )
        ),
        opt(
            "genesis_plus_gx_aspect_ratio", "Core-Provided Aspect Ratio",
            listOf("auto", "NTSC PAR", "PAL PAR", "4:3", "Uncorrected"),
            "auto",
            mapOf("auto" to "Auto")
        ),
        opt(
            "genesis_plus_gx_overscan", "Borders",
            listOf("disabled", "top/bottom", "left/right", "full"),
            "disabled",
            mapOf(
                "top/bottom" to "Top/Bottom",
                "left/right" to "Left/Right", "full" to "Full"
            )
        ),
        opt(
            "genesis_plus_gx_left_border",
            "Hide Master System Side Borders",
            listOf(
                "disabled", "left border", "left & right borders"
            ),
            "disabled",
            mapOf(
                "left border" to "Left Border Only",
                "left & right borders" to "Left & Right Borders"
            )
        ),
        opt(
            "genesis_plus_gx_gg_extra", "Game Gear Extended Screen",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_blargg_ntsc_filter", "Blargg NTSC Filter",
            listOf(
                "disabled", "monochrome", "composite", "svideo", "rgb"
            ),
            "disabled",
            mapOf("svideo" to "S-Video")
        ),
        opt(
            "genesis_plus_gx_lcd_filter", "LCD Ghosting Filter",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_render", "Interlaced Mode 2 Output",
            listOf("single field", "double field"), "single field",
            mapOf(
                "single field" to "Single Field",
                "double field" to "Double Field"
            )
        ),
        opt(
            "genesis_plus_gx_frameskip", "Frameskip",
            listOf("disabled", "auto", "manual"), "disabled",
            mapOf("auto" to "Auto", "manual" to "Manual")
        ),
        opt(
            "genesis_plus_gx_frameskip_threshold",
            "Frameskip Threshold (%)",
            range(15, 60, 3), "33"
        ),
        opt(
            "genesis_plus_gx_ym2413", "Master System FM (YM2413)",
            listOf("auto", "disabled", "enabled"), "auto",
            mapOf("auto" to "Auto")
        ),
        opt(
            "genesis_plus_gx_ym2413_core",
            "Master System FM (YM2413) Core",
            listOf("mame", "nuked"), "mame",
            mapOf("mame" to "MAME", "nuked" to "Nuked")
        ),
        opt(
            "genesis_plus_gx_ym2612", "Mega Drive/Genesis FM",
            listOf(
                "mame (ym2612)", "mame (asic ym3438)",
                "mame (enhanced ym3438)", "nuked (ym2612)",
                "nuked (ym3438)"
            ),
            "mame (ym2612)",
            mapOf(
                "mame (ym2612)" to "MAME (YM2612)",
                "mame (asic ym3438)" to "MAME (ASIC YM3438)",
                "mame (enhanced ym3438)" to "MAME (Enhanced YM3438)",
                "nuked (ym2612)" to "Nuked (YM2612)",
                "nuked (ym3438)" to "Nuked (YM3438)"
            )
        ),
        opt(
            "genesis_plus_gx_sound_output", "Sound Output",
            listOf("stereo", "mono"), "stereo",
            mapOf("stereo" to "Stereo", "mono" to "Mono")
        ),
        opt(
            "genesis_plus_gx_audio_filter", "Audio Filter",
            listOf("disabled", "low-pass", "EQ"), "disabled",
            mapOf("low-pass" to "Low-Pass")
        ),
        opt(
            "genesis_plus_gx_lowpass_range", "Low-Pass Filter %",
            range(5, 95, 5), "60"
        ),
        opt(
            "genesis_plus_gx_psg_preamp", "PSG Preamp Level",
            range(0, 200, 5), "150"
        ),
        opt(
            "genesis_plus_gx_fm_preamp", "FM Preamp Level",
            range(0, 200, 5), "100"
        ),
        opt(
            "genesis_plus_gx_gun_input", "Light Gun Input",
            listOf("lightgun", "touchscreen"), "lightgun",
            mapOf(
                "lightgun" to "Light Gun",
                "touchscreen" to "Touchscreen"
            )
        ),
        opt(
            "genesis_plus_gx_gun_cursor", "Show Light Gun Crosshair",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_invert_mouse", "Invert Mouse Y-Axis",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_no_sprite_limit",
            "Remove Per-Line Sprite Limit",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_enhanced_vscroll",
            "Enhanced per-tile vertical scroll",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "genesis_plus_gx_enhanced_vscroll_limit",
            "Enhanced per-tile vertical scroll limit",
            range(2, 16, 1), "8"
        ),
        opt(
            "genesis_plus_gx_overclock", "CPU Speed",
            range(100, 500, 25), "100"
        ),
        opt(
            "genesis_plus_gx_force_dtack", "System Lock-Ups",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "genesis_plus_gx_addr_error", "68K Address Error",
            listOf("enabled", "disabled"), "enabled"
        )
    )

    private val swanstation = listOf(
        opt(
            "swanstation_Console_Region", "Console Region",
            listOf("Auto", "NTSC-J", "NTSC-U", "PAL"), "Auto"
        ),
        boolOpt(
            "swanstation_BIOS_PatchFastBoot", "Fast Boot", "false"
        ),
        boolOpt(
            "swanstation_CDROM_RegionCheck", "CD-ROM Region Check", "false"
        ),
        boolOpt(
            "swanstation_CDROM_ReadThread", "CD-ROM Read Thread", "true"
        ),
        boolOpt(
            "swanstation_CDROM_LoadImagePatches",
            "Apply Image Patches (Restart)", "false"
        ),
        boolOpt(
            "swanstation_CDROM_LoadImageToRAM",
            "Preload CD-ROM Image To RAM", "false"
        ),
        boolOpt(
            "swanstation_CDROM_PreCacheCHD",
            "Pre-cache CHD Images To RAM", "false"
        ),
        boolOpt(
            "swanstation_CDROM_MuteCDAudio", "Mute CD Audio", "false"
        ),
        opt(
            "swanstation_CDROM_ReadSpeedup", "CD-ROM Read Speedup",
            range(1, 10, 1), "1"
        ),
        opt(
            "swanstation_CDROM_SeekSpeedup", "CD-ROM Seek Speedup",
            range(0, 10, 1), "1"
        ),
        opt(
            "swanstation_CDROM_ReadaheadSectors", "CD-ROM Async Readahead",
            listOf("0", "4", "8", "16", "32"), "8"
        ),
        opt(
            "swanstation_CPU_ExecutionMode", "CPU Execution Mode",
            listOf("Interpreter", "CachedInterpreter", "Recompiler"),
            "Recompiler"
        ),
        opt(
            "swanstation_CPU_Overclock", "CPU Overclocking",
            listOf("50", "75", "100", "125", "150", "200", "300"), "100"
        ),
        opt(
            "swanstation_GPU_ResolutionScale", "Internal Resolution Scale",
            listOf("1", "2", "3", "4", "8"), "1"
        ),
        boolOpt(
            "swanstation_GPU_UseThread",
            "Threaded Rendering (Software)", "true"
        ),
        boolOpt(
            "swanstation_GPU_TrueColor", "True Color Rendering", "false"
        ),
        boolOpt(
            "swanstation_GPU_ScaledDithering", "Scaled Dithering", "true"
        ),
        boolOpt(
            "swanstation_GPU_DisableInterlacing", "Disable Interlacing", "true"
        ),
        boolOpt(
            "swanstation_GPU_ForceNTSCTimings", "Force NTSC Timings", "false"
        ),
        boolOpt(
            "swanstation_Display_Force4_3For24Bit",
            "Force 4:3 For 24-Bit Display", "false"
        ),
        boolOpt(
            "swanstation_GPU_ChromaSmoothing24Bit",
            "Chroma Smoothing For 24-Bit Display", "false"
        ),
        opt(
            "swanstation_GPU_TextureFilter", "Texture Filtering",
            listOf(
                "Nearest", "Bilinear", "BilinearBinAlpha", "JINC2",
                "JINC2BinAlpha", "xBR", "xBRBinAlpha"
            ),
            "Nearest"
        ),
        boolOpt(
            "swanstation_GPU_WidescreenHack", "Widescreen Hack", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPEnable", "PGXP Geometry Correction", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPCulling", "PGXP Culling Correction", "true"
        ),
        boolOpt(
            "swanstation_GPU_PGXPTextureCorrection",
            "PGXP Perspective Correct Textures", "true"
        ),
        boolOpt(
            "swanstation_GPU_PGXPColorCorrection",
            "PGXP Perspective Correct Colors", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPDepthBuffer", "PGXP Depth Buffer", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPVertexCache", "PGXP Vertex Cache", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPCPU", "PGXP CPU Mode", "false"
        ),
        boolOpt(
            "swanstation_GPU_PGXPPreserveProjFP",
            "PGXP Preserve Projection Precision", "false"
        ),
        opt(
            "swanstation_Display_AspectRatio", "Core Provided Aspect Ratio",
            listOf("4:3", "16:9", "19:9", "20:9", "Auto", "Native", "PAR 1:1"),
            "4:3"
        ),
        opt(
            "swanstation_Display_CropMode", "Crop Mode",
            listOf("None", "Overscan", "Borders"), "Borders"
        ),
        boolOpt(
            "swanstation_Main_ApplyGameSettings",
            "Apply Compatibility Settings", "true"
        ),
        boolOpt(
            "swanstation_Audio_FastHook",
            "Use Per-Frame Audio Batching (Restart)", "true"
        )
    )

    private val picodrive = listOf(
        opt(
            "picodrive_region", "System Region",
            listOf(
                "Auto", "Japan NTSC", "Japan PAL", "US", "Europe"
            ), "Auto"
        ),
        opt(
            "picodrive_aspect", "Core-Provided Aspect Ratio",
            listOf("PAR", "4/3", "CRT"), "PAR",
            mapOf("4/3" to "4:3")
        ),
        opt(
            "picodrive_renderer", "Video Renderer",
            listOf("accurate", "good", "fast"), "accurate",
            mapOf(
                "accurate" to "Accurate", "good" to "Good",
                "fast" to "Fast"
            )
        ),
        opt(
            "picodrive_sound_rate", "Audio Sample Rate (Hz)",
            listOf("16000", "22050", "32000", "44100", "native"),
            "44100", mapOf("native" to "Native")
        ),
        opt(
            "picodrive_fm_filter", "FM Filtering",
            listOf("off", "on"), "off",
            mapOf("off" to "Disabled", "on" to "Enabled")
        ),
        opt(
            "picodrive_fmchip", "Mega Drive FM Chip Type",
            listOf("ym2612", "ym3438"), "ym3438",
            mapOf("ym2612" to "YM2612", "ym3438" to "YM3438")
        ),
        opt(
            "picodrive_audio_filter", "Audio Filter",
            listOf("disabled", "low-pass"), "disabled",
            mapOf("low-pass" to "Low-Pass")
        ),
        opt(
            "picodrive_lowpass_range", "Low-Pass Filter %",
            listOf("5", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55", "60", "65", "70", "75", "80", "85", "90", "95"), "60"
        ),
        opt(
            "picodrive_input1", "Input Device 1",
            listOf(
                "3 button pad", "6 button pad", "team player",
                "4way play", "None"
            ), "3 button pad",
            mapOf(
                "3 button pad" to "3 Button Pad",
                "6 button pad" to "6 Button Pad",
                "team player" to "Sega 4 Player Adaptor",
                "4way play" to "EA 4way Play Adaptor"
            )
        ),
        opt(
            "picodrive_input2", "Input Device 2",
            listOf("3 button pad", "6 button pad", "None"),
            "3 button pad",
            mapOf(
                "3 button pad" to "3 Button Pad",
                "6 button pad" to "6 Button Pad"
            )
        ),
        opt(
            "picodrive_drc", "Dynamic Recompilers",
            listOf("enabled", "disabled"), "enabled"
        ),
        opt(
            "picodrive_frameskip", "Frameskip",
            listOf("disabled", "auto", "manual"), "disabled",
            mapOf("auto" to "Auto", "manual" to "Manual")
        ),
        opt(
            "picodrive_frameskip_threshold",
            "Frameskip Threshold (%)",
            listOf("15", "18", "21", "24", "27", "30", "33", "36", "39", "42", "45", "48", "51", "54", "57", "60"), "33"
        ),
        opt(
            "picodrive_sprlim", "No Sprite Limit",
            listOf("disabled", "enabled"), "disabled"
        ),
        opt(
            "picodrive_overclk68k", "68K Overclock",
            listOf(
                "disabled", "+25%", "+50%", "+75%", "+100%",
                "+200%", "+400%"
            ), "disabled"
        )
    )


    fun forConsole(console: String): List<CoreOption> = when (console) {
        "nes" -> fceumm
        "snes" -> snes9x
        "genesis", "sms", "sg1000", "segacd" -> genesisPlusGx
        "ps1" -> swanstation
        "32x" -> picodrive
        else -> emptyList()
    }

    private fun settingKey(console: String, key: String) =
        "coreOpt:$console:$key"

    fun storedValue(context: Context, console: String, option: CoreOption): String {
        val raw = AppSettings.getString(context, settingKey(console, option.key), "")
        return if (raw in option.values) raw else option.default
    }

    fun setValue(context: Context, console: String, key: String, value: String) {
        AppSettings.putString(context, settingKey(console, key), value)
    }

    /** "key=value" pairs for the engine, which answers GET_VARIABLE
     * from exactly this map. */
    fun storedPairs(context: Context, console: String): Array<String> =
        forConsole(console).map { option ->
            option.key + "=" + storedValue(context, console, option)
        }.toTypedArray()
}
