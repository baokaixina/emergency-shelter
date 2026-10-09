package com.emergencyshelter.early;

import java.util.ArrayList;
import java.util.List;

/** mods 文件夹没有变化时，直接复用上次的结论，不再逐个读取模组。 */
final class AnalysisCache {
    String fingerprint;
    /** 需要跳过的文件名 */
    List<String> claimed = new ArrayList<>();
    LaunchReport report;
}
