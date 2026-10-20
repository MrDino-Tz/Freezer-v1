package com.dtcgroup.freezer.shizuku;

interface IPmService {
    // 16777114 is the transaction code the Shizuku server reserves for destroy().
    void destroy() = 16777114;

    // Runs `sh -c <command>` inside the Shizuku server process (uid 2000 / root).
    // Merged stdout+stderr is returned; the exit code is stored and read separately.
    String exec(String command) = 1;

    int lastExitCode() = 2;
}
