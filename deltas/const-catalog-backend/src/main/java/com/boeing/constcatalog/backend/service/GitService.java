package com.boeing.constcatalog.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Executes git CLI commands via {@link ProcessBuilder} for creating branches
 * with transformed files. All operations have a 30-second timeout.
 */
@Service
@Slf4j
public class GitService {

    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Checks whether the given directory is inside a git repository.
     */
    public boolean isGitRepo(Path projectDir) {
        try {
            int exitCode = runGit(projectDir, "rev-parse", "--is-inside-work-tree");
            return exitCode == 0;
        } catch (GitException e) {
            return false;
        }
    }

    /**
     * Returns the current branch name (e.g. "main" or "feature/foo").
     */
    public String getCurrentBranch(Path projectDir) throws GitException {
        return runGitOutput(projectDir, "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /**
     * Creates a new branch and switches to it. Fails if the branch already exists.
     */
    public void createAndCheckoutBranch(Path projectDir, String branchName) throws GitException {
        runGit(projectDir, "checkout", "-b", branchName);
        log.info("Created and checked out branch: {}", branchName);
    }

    /**
     * Switches to an existing branch.
     */
    public void checkoutBranch(Path projectDir, String branchName) throws GitException {
        runGit(projectDir, "checkout", branchName);
        log.info("Checked out branch: {}", branchName);
    }

    /**
     * Stages all changes and commits with the given message.
     */
    public void addAndCommit(Path projectDir, String message) throws GitException {
        runGit(projectDir, "add", "-A");
        runGit(projectDir, "commit", "-m", message);
        log.info("Committed changes: {}", message);
    }

    /**
     * Stages only the specified files and commits with the given message.
     * This avoids accidentally staging unrelated changes in the working tree.
     *
     * @param projectDir the git repository root
     * @param files      absolute paths to files that should be staged
     * @param message    commit message
     */
    public void addFilesAndCommit(Path projectDir, java.util.Collection<String> files, String message) throws GitException {
        for (String file : files) {
            runGit(projectDir, "add", file);
        }
        runGit(projectDir, "commit", "-m", message);
        log.info("Committed {} files: {}", files.size(), message);
    }

    /**
     * Initialises a git repository in the given directory, commits all existing files
     * as a pre-transformation baseline, and normalises the branch name to {@code main}.
     *
     * <p>A local {@code user.name} / {@code user.email} are set so commits succeed even
     * in environments where no global git identity is configured.
     *
     * @param projectDir directory to initialise (must exist)
     * @return {@code "main"} — the branch name after initialisation
     * @throws GitException if any git command fails
     */
    public String initRepoWithInitialCommit(Path projectDir) throws GitException {
        runGit(projectDir, "init");
        // Set a local identity in case no global git config is present
        runGit(projectDir, "config", "user.email", "const-catalog@tool.local");
        runGit(projectDir, "config", "user.name", "Const Catalog");
        runGit(projectDir, "add", "-A");
        runGit(projectDir, "commit", "--allow-empty", "-m", "Initial state (pre-transformation)");
        // Rename whatever the default branch is (master, main, etc.) to "main"
        String current = runGitOutput(projectDir, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!"main".equals(current)) {
            runGit(projectDir, "branch", "-m", "main");
        }
        log.info("Initialised git repo with initial commit in: {}", projectDir);
        return "main";
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private int runGit(Path workDir, String... args) throws GitException {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitException("git command timed out after " + TIMEOUT_SECONDS + "s: git " + String.join(" ", args));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new GitException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output.trim());
            }
            return exitCode;
        } catch (GitException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitException("Failed to run git " + String.join(" ", args) + ": " + e.getMessage(), e);
        }
    }

    private String runGitOutput(Path workDir, String... args) throws GitException {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitException("git command timed out after " + TIMEOUT_SECONDS + "s: git " + String.join(" ", args));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new GitException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output.trim());
            }
            return output;
        } catch (GitException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitException("Failed to run git " + String.join(" ", args) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checked exception for git operation failures.
     */
    public static class GitException extends Exception {
        public GitException(String message) {
            super(message);
        }

        public GitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
