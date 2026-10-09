package com.emergencyshelter.early;

import java.util.ArrayList;
import java.util.List;

/** 启动失败时（在当次进程里）记下的、能明确对应到某个模组文件的错误。下次启动时读取。 */
final class PendingIssues {
    long time = System.currentTimeMillis();
    List<Culprit> culprits = new ArrayList<>();

    static final class Culprit {
        String file;
        String modId;
        String message;

        Culprit() {
        }

        Culprit(String file, String modId, String message) {
            this.file = file;
            this.modId = modId;
            this.message = message;
        }
    }
}
