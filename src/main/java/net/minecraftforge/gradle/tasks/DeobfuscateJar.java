package net.minecraftforge.gradle.tasks;

import static net.minecraftforge.gradle.common.Constants.EXT_NAME_MC;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.md_5.specialsource.AccessMap;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemapperProcessor;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.common.BaseExtension;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.util.caching.Cached;
import net.minecraftforge.gradle.util.caching.CachedTask;
import net.minecraftforge.gradle.util.delayed.DelayedFile;
import net.minecraftforge.gradle.util.json.JsonFactory;
import net.minecraftforge.gradle.util.json.MCInjectorStruct;
import net.minecraftforge.gradle.util.json.MCInjectorStruct.InnerClass;

import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;

import de.oceanlabs.mcp.mcinjector.MCInjectorImpl;

public class DeobfuscateJar extends CachedTask
{
    @InputFile
    @Optional
    private Object            fieldCsv;
    @InputFile
    @Optional
    private Object            methodCsv;

    @InputFile
    private Object            inJar;

    @InputFile
    private Object            srg;

    @InputFile
    private Object            exceptorCfg;

    @Optional
    @Input
    private boolean           stripSynthetics = false;

    @InputFile
    private Object            exceptorJson;

    @Input
    private boolean           applyMarkers    = false;
    
    @Input
    private boolean           failOnAtError   = true;

    private Object            outCleanJar;                                                                 // clean = pure forge, or pure FML
    private Object            outDirtyJar     = new DelayedFile(getProject(), "{BUILD_DIR}/processed.jar"); // dirty = has any other ATs

    @InputFiles
    private ArrayList<Object> ats             = Lists.newArrayList();

    private Object            log;

    private boolean           isClean         = true;

    public void addTransformerClean(Object... obj)
    {
        for (Object object : obj)
        {
            ats.add(object);
        }
    }

    /**
     * adds an access transformer to the deobfuscation of this
     * @param obj access transformers
     */
    public void addTransformer(Object... obj)
    {
        for (Object object : obj)
        {
            if (object instanceof File)
                ats.add(new DelayedFile(getProject(), ((File) object).getAbsolutePath()));
            else if (object instanceof String)
                ats.add(new DelayedFile(getProject(), (String) object));
            else
                ats.add(new DelayedFile(getProject(), object.toString()));

            isClean = false;
        }
    }

    @TaskAction
    public void doTask() throws IOException
    {
        // make stuff into files.
        File tempObfJar = new File(getTemporaryDir(), "deobfed.jar"); // courtesy of gradle temp dir.
        File out = isClean ? getOutCleanJar() : getOutDirtyJar();
        File tempExcJar = stripSynthetics ? new File(getTemporaryDir(), "excpeted.jar") : out; // courtesy of gradle temp dir.

        // make the ATs list.. its a Set to avoid duplication.
        Set<File> ats = new HashSet<File>();
        for (Object obj : this.ats)
        {
            ats.add(getProject().file(obj).getCanonicalFile());
        }

        // deobf
        getLogger().lifecycle("Applying SpecialSource...");
        deobfJar(getInJar(), tempObfJar, getSrg(), ats);

        File log = getLog();
        if (log == null)
            log = new File(getTemporaryDir(), "exceptor.log");

        // apply exceptor
        getLogger().lifecycle("Applying Exceptor...");
        applyExceptor(tempObfJar, tempExcJar, getExceptorCfg(), log, ats);

        if (stripSynthetics)
        {
            // strip out synthetics that arnt from enums..
            getLogger().lifecycle("Stripping synthetics...");
            stripSynthetics(tempExcJar, out);
        }
    }

    private void deobfJar(File inJar, File outJar, File srg, Collection<File> ats) throws IOException
    {
        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(srg);

        // load in ATs
        ErroringRemappingAccessMap accessMap = new ErroringRemappingAccessMap(new File[] { getMethodCsv(), getFieldCsv() });

        getLogger().info("Using AccessTransformers...");
//        PrintStream tmp = System.out;
//        System.setOut(new PrintStream(new ByteArrayOutputStream()
//        {
//            @Override
//            public void write(int b)
//            {
//            }
//
//            @Override
//            public void write(byte[] b, int off, int len)
//            {
//            }
//
//            @Override
//            public void writeTo(OutputStream out) throws IOException
//            {
//            }
//        }));
        //Make SS shutup about access maps
        for (File at : ats)
        {
            getLogger().info("" + at);
            accessMap.loadAccessTransformer(at);
        }
//        System.setOut(tmp);

        // make a processor out of the ATS and mappings.
        RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);

        RemapperProcessor atProcessor = new RemapperProcessor(null, null, accessMap);
        // make remapper
        JarRemapper remapper = new JarRemapper(srgProcessor, mapping, atProcessor);

        // load jar
        Jar input = Jar.init(inJar);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));
        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        // remap jar
        remapper.remapJar(input, outJar);
        
        // throw error for broken AT lines
        if (accessMap.brokenLines.size() > 0 && failOnAtError)
        {
            getLogger().error("{} Broken Access Transformer lines:", accessMap.brokenLines.size());
            for (String line : accessMap.brokenLines.values())
            {
                getLogger().error(" ---  {}", line);
            }
            
            // TODO: add info for disabling
            
            throw new RuntimeException("Your Access Transformers be broke!");
        }
    }

    private int fixAccess(int access, String target)
    {
        int ret = access & ~7;
        int t = 0;

        if (target.startsWith("public"))
            t = ACC_PUBLIC;
        else if (target.startsWith("private"))
            t = ACC_PRIVATE;
        else if (target.startsWith("protected"))
            t = ACC_PROTECTED;

        switch (access & 7)
            {
                case ACC_PRIVATE:
                    ret |= t;
                    break;
                case 0:
                    ret |= (t != ACC_PRIVATE ? t : 0);
                    break;
                case ACC_PROTECTED:
                    ret |= (t != ACC_PRIVATE && t != 0 ? t : ACC_PROTECTED);
                    break;
                case ACC_PUBLIC:
                    ret |= ACC_PUBLIC;
                    break;
            }

        if (target.endsWith("-f"))
            ret &= ~ACC_FINAL;
        else if (target.endsWith("+f"))
            ret |= ACC_FINAL;
        return ret;
    }

    public void applyExceptor(File inJar, File outJar, File config, File log, Set<File> ats) throws IOException
    {
        String json = null;
        File getJson = getExceptorJson();
        if (getJson != null)
        {
            final Map<String, MCInjectorStruct> struct = JsonFactory.loadMCIJson(getJson);
            for (File at : ats)
            {
                getLogger().info("loading AT: " + at.getCanonicalPath());

                Files.readLines(at, Charset.defaultCharset(), new LineProcessor<Object>()
                {
                    @Override
                    public boolean processLine(String line) throws IOException
                    {
                        if (line.indexOf('#') != -1)
                            line = line.substring(0, line.indexOf('#'));
                        line = line.trim().replace('.', '/');
                        if (line.isEmpty())
                            return true;

                        String[] s = line.split(" ");
                        if (s.length == 2 && s[1].indexOf('$') > 0)
                        {
                            String parent = s[1].substring(0, s[1].indexOf('$'));
                            for (MCInjectorStruct cls : new MCInjectorStruct[] { struct.get(parent), struct.get(s[1]) })
                            {
                                if (cls != null && cls.innerClasses != null)
                                {
                                    for (InnerClass inner : cls.innerClasses)
                                    {
                                        if (inner.inner_class.equals(s[1]))
                                        {
                                            int access = fixAccess(inner.getAccess(), s[0]);
                                            inner.access = (access == 0 ? null : Integer.toHexString(access));
                                        }
                                    }
                                }
                            }
                        }

                        return true;
                    }

                    @Override
                    public Object getResult()
                    {
                        return null;
                    }
                });
            }
            File jsonTmp = new File(this.getTemporaryDir(), "transformed.json");
            json = jsonTmp.getCanonicalPath();
            Files.write(JsonFactory.GSON.toJson(struct).getBytes(), jsonTmp);
        }

        BaseExtension exten = (BaseExtension) getProject().getExtensions().getByName(EXT_NAME_MC);
        boolean genParams = !exten.getVersion().equals("1.7.2");
        getLogger().debug("INPUT: " + inJar);
        getLogger().debug("OUTPUT: " + outJar);
        getLogger().debug("CONFIG: " + config);
        getLogger().debug("JSON: " + json);
        getLogger().debug("LOG: " + log);
        getLogger().debug("PARAMS: " + genParams);

        MCInjectorImpl.process(inJar.getCanonicalPath(),
                outJar.getCanonicalPath(),
                config.getCanonicalPath(),
                log.getCanonicalPath(),
                null,
                0,
                json,
                isApplyMarkers(),
                genParams);
    }

    private void stripSynthetics(File inJar, File outJar) throws IOException
    {
        ZipFile in = new ZipFile(inJar);
        final ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outJar)));

        for (ZipEntry e : Collections.list(in.entries()))
        {
            if (e.getName().contains("META-INF"))
                continue;

            if (e.isDirectory())
            {
                out.putNextEntry(e);
            }
            else
            {
                ZipEntry n = new ZipEntry(e.getName());
                n.setTime(e.getTime());
                out.putNextEntry(n);

                byte[] data = ByteStreams.toByteArray(in.getInputStream(e));

                // correct source name
                if (e.getName().endsWith(".class"))
                    data = stripSynthetics(e.getName(), data);

                out.write(data);
            }
        }

        out.flush();
        out.close();
        in.close();
    }

    private byte[] stripSynthetics(String name, byte[] data)
    {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();

        reader.accept(node, 0);

        if ((node.access & Opcodes.ACC_ENUM) == 0 && !node.superName.equals("java/lang/Enum") && (node.access & Opcodes.ACC_SYNTHETIC) == 0)
        {
            // ^^ is for ignoring enums.

            for (FieldNode f : ((List<FieldNode>) node.fields))
            {
                f.access = f.access & (0xffffffff - Opcodes.ACC_SYNTHETIC);
                //getLogger().lifecycle("Stripping field: "+f.name);
            }

            for (MethodNode m : ((List<MethodNode>) node.methods))
            {
                m.access = m.access & (0xffffffff - Opcodes.ACC_SYNTHETIC);
                //getLogger().lifecycle("Stripping method: "+m.name);
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public File getExceptorCfg()
    {
        return getProject().file(exceptorCfg);
    }

    public void setExceptorCfg(DelayedFile exceptorCfg)
    {
        this.exceptorCfg = exceptorCfg;
    }

    public File getExceptorJson()
    {
        if (exceptorJson == null)
            return null;
        else
            return getProject().file(exceptorJson);
    }

    public void setExceptorJson(DelayedFile exceptorJson)
    {
        this.exceptorJson = exceptorJson;
    }

    public boolean isApplyMarkers()
    {
        return applyMarkers;
    }

    public void setApplyMarkers(boolean applyMarkers)
    {
        this.applyMarkers = applyMarkers;
    }

    public boolean isFailOnAtError()
    {
        return failOnAtError;
    }

    public void setFailOnAtError(boolean failOnAtError)
    {
        this.failOnAtError = failOnAtError;
    }

    public File getInJar()
    {
        return getProject().file(inJar);
    }

    public void setInJar(DelayedFile inJar)
    {
        this.inJar = inJar;
    }

    public File getLog()
    {
        if (log == null)
            return null;
        else
            return getProject().file(log);
    }

    public void setLog(Object log)
    {
        this.log = log;
    }

    public File getSrg()
    {
        return getProject().file(srg);
    }

    public void setSrg(Object srg)
    {
        this.srg = srg;
    }

    public File getOutCleanJar()
    {
        return getProject().file(outCleanJar);
    }

    public void setOutCleanJar(Object outJar)
    {
        this.outCleanJar = outJar;
    }

    public File getOutDirtyJar()
    {
        return getProject().file(outDirtyJar);
    }

    public void setOutDirtyJar(DelayedFile outDirtyJar)
    {
        this.outDirtyJar = outDirtyJar;
    }

    public boolean isClean()
    {
        return isClean;
    }

    /**
     * returns the actual output DelayedFile depending on Clean status
     * Unlike getOutputJar() this method does not resolve the files.
     * @return DelayedFIle that will resolve to
     */
    public Object getDelayedOutput()
    {
        return isClean ? outCleanJar : outDirtyJar;
    }

    /**
     * returns the actual output file depending on Clean status
     * @return File representing output jar
     */
    @Cached
    @OutputFile
    public File getOutJar()
    {
        return getProject().file(getDelayedOutput());
    }

    public FileCollection getAts()
    {
        return getProject().files(ats.toArray());
    }

    public File getFieldCsv()
    {
        return fieldCsv == null ? null : getProject().file(fieldCsv);
    }

    public void setFieldCsv(Object fieldCsv)
    {
        this.fieldCsv = fieldCsv;
    }

    public File getMethodCsv()
    {
        return methodCsv == null ? null : getProject().file(methodCsv);
    }

    public void setMethodCsv(Object methodCsv)
    {
        this.methodCsv = methodCsv;
    }

    @Override
    protected boolean defaultCache()
    {
        return isClean();
    }

    public void setDirty()
    {
        isClean = false;
    }

    public boolean getStripSynthetics()
    {
        return stripSynthetics;
    }

    public void setStripSynthetics(boolean stripSynthetics)
    {
        this.stripSynthetics = stripSynthetics;
    }

    private static final class ErroringRemappingAccessMap extends AccessMap
    {
        private final Map<String, String> renames = Maps.newHashMap();
        public final Map<String, String> brokenLines = Maps.newHashMap();

        public ErroringRemappingAccessMap(File[] renameCsvs) throws IOException
        {
            super();

            for (File f : renameCsvs)
            {
                if (f == null)
                    continue;
                Files.readLines(f, Charsets.UTF_8, new LineProcessor<String>()
                {
                    @Override
                    public boolean processLine(String line) throws IOException
                    {
                        String[] pts = line.split(",");
                        if (!"searge".equals(pts[0]))
                        {
                            renames.put(pts[0], pts[1]);
                        }

                        return true;
                    }

                    @Override
                    public String getResult()
                    {
                        return null;
                    }
                });
            }
        }

        @Override
        public void loadAccessTransformer(File file) throws IOException
        {
            // because SS doesnt close its freaking reader...
            BufferedReader reader = Files.newReader(file, Constants.CHARSET);
            loadAccessTransformer(reader);
            reader.close();
        }

        @Override
        public void addAccessChange(String symbolString, String accessString)
        {
            String[] pts = symbolString.split(" ");
            if (pts.length >= 2)
            {
                int idx = pts[1].indexOf('(');

                String start = pts[1];
                String end = "";

                if (idx != -1)
                {
                    start = pts[1].substring(0, idx);
                    end = pts[1].substring(idx);
                }

                String rename = renames.get(start);
                if (rename != null)
                {
                    pts[1] = rename + end;
                }
            }
            String joinedString = Joiner.on('.').join(pts);
            super.addAccessChange(joinedString, accessString);
            // convert  package.class  to  package/class
            brokenLines.put(joinedString.replace('.', '/'), symbolString);
        }
        
        @Override
        protected void accessApplied(String key, int oldAccess, int newAccess)
        {
            // if the access' are equal, then the line is broken, and we dont want to remove it.
            if (oldAccess != newAccess)
            {
                // key added before is in format: package/class{method/field sig}
                // and the key here is in format: package/class {method/field sig}
                brokenLines.remove(key.replace(" ", ""));
            }
        }
    }
}
