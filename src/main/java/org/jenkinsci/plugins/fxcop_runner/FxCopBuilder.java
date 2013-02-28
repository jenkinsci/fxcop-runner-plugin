package org.jenkinsci.plugins.fxcop_runner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.jenkinsci.plugins.fxcop_runner.Messages;
import org.jenkinsci.plugins.fxcop_runner.util.StringUtil;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.tasks.Builder;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;

/**
 * @author Yasuyuki Saito
 */
public class FxCopBuilder extends Builder {

    private final String toolName;
    private final String files;
    private final String outputXML;
    private final String ruleSet;
    private final boolean ignoreGeneratedCode;
    private final boolean forceOutput;
    private final String cmdLineArgs;
    private final boolean failBuild;

    /**
     *
     * @param toolName
     * @param files
     * @param outputXML
     * @param ruleSet
     * @param ignoreGeneratedCode
     * @param forceOutput
     * @param cmdLineArgs
     * @param failBuild
     */
    @DataBoundConstructor
    public FxCopBuilder(String toolName, String files, String outputXML, String ruleSet, boolean ignoreGeneratedCode, boolean forceOutput, String cmdLineArgs, boolean failBuild) {
        this.toolName            = toolName;
        this.files               = files;
        this.outputXML           = outputXML;
        this.ruleSet             = ruleSet;
        this.ignoreGeneratedCode = ignoreGeneratedCode;
        this.forceOutput         = forceOutput;
        this.cmdLineArgs         = cmdLineArgs;
        this.failBuild           = failBuild;
    }

    public String getToolName() {
        return toolName;
    }

    public String getFiles() {
        return files;
    }

    public String getOutputXML() {
        return outputXML;
    }

    public String getRuleSet() {
        return ruleSet;
    }

    public boolean isIgnoreGeneratedCode() {
        return ignoreGeneratedCode;
    }

    public boolean isForceOutput() {
        return forceOutput;
    }

    public String getCmdLineArgs() {
        return cmdLineArgs;
    }

    public boolean isFailBuild() {
        return failBuild;
    }

    public FxCopInstallation getInstallation() {
        if (toolName == null) return null;
        for (FxCopInstallation i : DESCRIPTOR.getInstallations()) {
            if (toolName.equals(i.getName()))
                return i;
        }
        return null;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        ArrayList<String> args = new ArrayList<String>();
        EnvVars env = build.getEnvironment(listener);

        // FxCopCmd.exe path.
        String toolPath = getToolPath(launcher, listener, env);
        if (StringUtil.isNullOrSpace(toolPath)) return false;
        args.add(toolPath);

        // Assembly file(s) to analyze.
        if (!StringUtil.isNullOrSpace(files))
            args.addAll(getArguments(build, env, "file", files));

        // FxCop project or XML report output file.
        if (!StringUtil.isNullOrSpace(outputXML)) {
            args.add(StringUtil.convertArgumentWithQuote("out", outputXML));

            FilePath outputXMLPath = build.getWorkspace().child(outputXML);
            if (outputXMLPath.exists())
                outputXMLPath.delete();
            else
                outputXMLPath.getParent().mkdirs();
        }

        // Rule set to be used for the analysis.
        if (!StringUtil.isNullOrSpace(ruleSet))
            args.add(String.format("/ruleset:=\"%s\"", ruleSet));

        // Suppress analysis results against generated code.
        if (ignoreGeneratedCode)
            args.add("/ignoregeneratedcode");

        // Write output XML and project files even in the case where no violations occurred.
        if (forceOutput)
            args.add("/forceoutput");

        // Manual Command Line String
        if (!StringUtil.isNullOrSpace(cmdLineArgs))
            args.add(cmdLineArgs);

        // Metrics.exe run.
        boolean r = execTool(args, build, launcher, listener, env);

        return r;
    }


    /**
     *
     * @param  launcher
     * @param  listener
     * @param  env
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    private String getToolPath(Launcher launcher, BuildListener listener, EnvVars env) throws InterruptedException, IOException {
        String execName = "FxCopCmd.exe";
        FxCopInstallation installation = getInstallation();

        if (installation == null) {
            listener.getLogger().println("Path To FxCopCmd.exe: " + execName);
            return execName;
        } else {
            installation = installation.forNode(Computer.currentComputer().getNode(), listener);
            installation = installation.forEnvironment(env);
            String pathToFxCop = installation.getHome();
            FilePath exec = new FilePath(launcher.getChannel(), pathToFxCop);

            try {
                if (!exec.exists()) {
                    listener.fatalError(pathToFxCop + " doesn't exist");
                    return null;
                }
            } catch (IOException e) {
                listener.fatalError("Failed checking for existence of " + pathToFxCop);
                return null;
            }

            listener.getLogger().println("Path To FxCopCmd.exe: " + pathToFxCop);
            return StringUtil.appendQuote(pathToFxCop);
        }
    }

    /**
     *
     * @param  build
     * @param  env
     * @param  option
     * @param  values
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    private List<String> getArguments(AbstractBuild<?, ?> build, EnvVars env, String option, String values) throws InterruptedException, IOException {
        ArrayList<String> args = new ArrayList<String>();
        StringTokenizer valuesToknzr = new StringTokenizer(values, " \t\r\n");

        while (valuesToknzr.hasMoreTokens()) {
            String value = valuesToknzr.nextToken();
            value = Util.replaceMacro(value, env);
            value = Util.replaceMacro(value, build.getBuildVariables());

            if (!StringUtil.isNullOrSpace(value))
                args.add(StringUtil.convertArgumentWithQuote(option, value));
        }

        return args;
    }

    /**
     *
     * @param  args
     * @param  build
     * @param  launcher
     * @param  listener
     * @param  env
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    private boolean execTool(List<String> args, AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, EnvVars env) throws InterruptedException, IOException {
        ArgumentListBuilder cmdExecArgs = new ArgumentListBuilder();
        FilePath tmpDir = null;
        FilePath pwd = build.getWorkspace();

        if (!launcher.isUnix()) {
            tmpDir = pwd.createTextTempFile("fxcop_runner", ".bat", StringUtil.concatString(args), false);
            cmdExecArgs.add("cmd.exe", "/C", tmpDir.getRemote(), "&&", "exit", "%ERRORLEVEL%");
        } else {
            for (String arg : args) {
                cmdExecArgs.add(arg);
            }
        }

        listener.getLogger().println("Executing FxCop: " + cmdExecArgs.toStringWithQuote());

        try {
            int r = launcher.launch().cmds(cmdExecArgs).envs(env).stdout(listener).pwd(pwd).join();

            if (failBuild)
                return (r == 0);
            else {
                if (r != 0)
                    build.setResult(Result.UNSTABLE);
                return true;
            }
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("FxCop execution failed"));
            return false;
        } finally {
            try {
                if (tmpDir != null) tmpDir.delete();
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError("temporary file delete failed"));
            }
        }
    }


    @Override
    public Descriptor<Builder> getDescriptor() {
         return DESCRIPTOR;
    }

    /**
     * Descriptor should be singleton.
     */
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**
     * @author Yasuyuki Saito
     */
    public static final class DescriptorImpl extends Descriptor<Builder> {

        @CopyOnWrite
        private volatile FxCopInstallation[] installations = new FxCopInstallation[0];

        DescriptorImpl() {
            super(FxCopBuilder.class);
            load();
        }

        public String getDisplayName() {
            return Messages.FxCopBuilder_DisplayName();
        }

        public FxCopInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(FxCopInstallation... installations) {
            this.installations = installations;
            save();
        }

        /**
         * Obtains the {@link FxCopInstallation.DescriptorImpl} instance.
         */
        public FxCopInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(FxCopInstallation.DescriptorImpl.class);
        }
    }
}
