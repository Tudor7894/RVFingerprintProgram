// Part of SourceAFIS for Java: https://sourceafis.machinezoo.com/java
package com.machinezoo.sourceafis;

import java.util.*;
import javax.imageio.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.annotation.JsonAutoDetect.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.cbor.*;
import com.google.gson.*;
import com.machinezoo.fingerprintio.*;
import com.machinezoo.noexception.*;
import com.machinezoo.sourceafis.engine.configuration.*;
import com.machinezoo.sourceafis.engine.extractor.*;
import com.machinezoo.sourceafis.engine.primitives.*;
import com.machinezoo.sourceafis.engine.templates.*;

/**
 * Biometric description of a fingerprint suitable for efficient matching.
 * Fingerprint template holds high-level fingerprint features, specifically ridge endings and bifurcations (together called minutiae).
 * Original image is not preserved in the fingerprint template and there is no way to reconstruct the original fingerprint from its template.
 * <p>
 * {@link FingerprintImage} can be converted to template by calling {@link #FingerprintTemplate(FingerprintImage)} constructor.
 * <p>
 * Since image processing is expensive, applications should cache serialized templates.
 * Serialization into CBOR format is performed by {@link #toByteArray()} method.
 * CBOR template can be deserialized by calling {@link #FingerprintTemplate(byte[])} constructor.
 * <p>
 * Matching is performed by constructing {@link FingerprintMatcher},
 * passing probe fingerprint to its {@link FingerprintMatcher#FingerprintMatcher(FingerprintTemplate)} constructor,
 * and then passing candidate fingerprints to its {@link FingerprintMatcher#match(FingerprintTemplate)} method.
 * <p>
 * {@code FingerprintTemplate} contains two kinds of data: fingerprint features and search data structures.
 * Search data structures speed up matching at the cost of some RAM.
 * Only fingerprint features are serialized. Search data structures are recomputed after every deserialization.
 * 
 * @see <a href="https://sourceafis.machinezoo.com/java">SourceAFIS for Java tutorial</a>
 * @see FingerprintImage
 * @see FingerprintMatcher
 */
public class FingerprintTemplate {
    /*
     * API roadmap:
     * + FingerprintTemplate(FingerprintImage, FingerprintTemplateOptions)
     * + double surface() - in metric units
     * + FingerprintPosition position()
     * + other fingerprint properties set in FingerprintImageOptions (only those relevant to matching, so no width/height for example)
     * + FingerprintModel model()
     * + FingerprintTemplate(FingerprintModel)
     * + FingerprintTemplate narrow(FingerprintTemplateOptions) - for reducing RAM usage by dropping features
     * + byte[] pack(int limit) - for producing super-compact templates (even under 100 bytes)
     * + FingerprintTemplate unpack(byte[] packed)
     * 
     * FingerprintTemplateOptions:
     * + featureX(boolean) - enable/disable production of expensive fingerprint features
     * + parallelize(boolean)
     * + cpu(long) - limit on CPU cycles consumed (approximate)
     * 
     * FingerprintModel:
     * = editing-optimized fingerprint representation as opposed to matching- and serialization-optimized FingerprintTemplate
     * = to be used in forensics and other settings for fingerprint editing
     * - no DPI, all values in metric units
     * + double width/height()
     * + List<FingerprintMinutia> minutiae() - mutable list of mutable minutiae
     * + all properties exposed by FingerprintTemplate
     * + setters for everything
     * 
     * FingerprintMinutia:
     * + double x/y()
     * + double direction()
     * + also setters
     * 
     * FingerprintFusion:
     * + add(FingerprintTemplate)
     * + FingerprintTemplate fuse()
     */
    static {
        PlatformCheck.run();
    }
    /*
     * We should drop this indirection once deprecated methods are dropped
     * and FingerprintTemplate itself becomes immutable.
     */
    volatile SearchTemplate inner = SearchTemplate.EMPTY;
    /**
     * Creates fingerprint template from fingerprint image.
     * <p>
     * This constructor runs an expensive feature extractor algorithm,
     * which analyzes the image and collects identifiable biometric features from it.
     * 
     * @param image
     *            fingerprint image to process
     * @throws NullPointerException
     *             if {@code image} is {@code null}
     */
    public FingerprintTemplate(FingerprintImage image) {
        Objects.requireNonNull(image);
        inner = new SearchTemplate(FeatureExtractor.extract(image.matrix, image.dpi));
    }
    /**
     * Deserializes fingerprint template from byte array.
     * This constructor reads <a href="https://cbor.io/">CBOR</a>-encoded template produced by {@link #toByteArray()}
     * and reconstructs an exact copy of the original fingerprint template.
     * <p>
     * Templates produced by previous versions of SourceAFIS may fail to deserialize correctly.
     * Applications should re-extract all templates from original images when upgrading SourceAFIS.
     * 
     * @param serialized
     *            serialized fingerprint template in <a href="https://cbor.io/">CBOR</a> format produced by {@link #toByteArray()}
     * @throws NullPointerException
     *             if {@code serialized} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code serialized} is not in the correct format or it is corrupted
     * 
     * @see #toByteArray()
     * @see <a href="https://sourceafis.machinezoo.com/template">Template format</a>
     * @see FingerprintImage#FingerprintImage(byte[])
     * @see FingerprintCompatibility#importTemplate(byte[])
     */
    public FingerprintTemplate(byte[] serialized) { this(serialized, true); }
    private static final ObjectMapper mapper = new ObjectMapper(new CBORFactory())
        .setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    FingerprintTemplate(byte[] serialized, boolean foreignToo) {
        try {
            Objects.requireNonNull(serialized);
            PersistentTemplate persistent = mapper.readValue(serialized, PersistentTemplate.class);
            persistent.validate();
            inner = new SearchTemplate(persistent.mutable());
        } catch (Throwable ex) {
            if (!foreignToo)
                throw new IllegalArgumentException("This is not a valid SourceAFIS template.", ex);
            /*
             * If it's not a native template, try foreign templates.
             */
            try {
                FingerprintCompatibility.importTemplates(serialized, Exceptions.silence());
            } catch (Throwable ex2) {
                /*
                 * Not a foreign template either. Throw the original exception.
                 */
                throw new IllegalArgumentException("This is not a valid SourceAFIS template.", ex);
            }
            throw new IllegalArgumentException("Use FingerprintCompatibility.importTemplate() to parse non-native template.");
        }
    }
    /**
     * @deprecated Use one of the constructors that take parameters to create fully initialized template instead.
     * 
     * @see #FingerprintTemplate(FingerprintImage)
     * @see #FingerprintTemplate(byte[])
     */
    @Deprecated public FingerprintTemplate() {}
    /**
     * Gets the empty fallback template with no biometric data.
     * Empty template is useful as a fallback to simplify code.
     * It contains no biometric data and it does not match any other template including itself.
     * There is only one global instance. This method does not instantiate any new objects.
     * 
     * @return empty template
     */
    public static FingerprintTemplate empty() { return EMPTY; }
    private static final FingerprintTemplate EMPTY = new FingerprintTemplate(SearchTemplate.EMPTY);
    FingerprintTemplate(SearchTemplate immutable) { this.inner = immutable; }
    /**
     * @deprecated Use thread-local instance of {@link FingerprintTransparency} instead.
     * 
     * @param transparency
     *            target {@link FingerprintTransparency} or {@code null} to disable algorithm transparency
     * @return {@code this} (fluent method)
     * 
     * @see FingerprintTransparency
     */
    @Deprecated public FingerprintTemplate transparency(FingerprintTransparency transparency) { return this; }
    private double dpi = 500;
    /**
     * @deprecated Set DPI via {@link FingerprintImageOptions}{@link #dpi(double)} instead.
     * 
     * @param dpi
     *            DPI of the fingerprint image, usually around 500
     * @return {@code this} (fluent method)
     * 
     * @see FingerprintImageOptions#dpi(double)
     */
    @Deprecated public FingerprintTemplate dpi(double dpi) {
        this.dpi = dpi;
        return this;
    }
    /**
     * @deprecated Use {@link #FingerprintTemplate(FingerprintImage)} constructor to create template from image.
     * 
     * @param image
     *            fingerprint image in {@link ImageIO}-supported format
     * @return {@code this} (fluent method)
     * 
     * @see #FingerprintTemplate(FingerprintImage)
     */
    @Deprecated public FingerprintTemplate create(byte[] image) {
        inner = new SearchTemplate(FeatureExtractor.extract(new FingerprintImage(image).matrix, dpi));
        return this;
    }
    /**
     * @deprecated Use {@link #FingerprintTemplate(byte[])} constructor to deserialize the template.
     * 
     * @param json
     *            serialized fingerprint template in JSON format produced by {@link #serialize()}
     * @return {@code this} (fluent method)
     * @throws NullPointerException
     *             if {@code json} is {@code null}
     * @throws RuntimeException
     *             if {@code json} is is not in the correct format or it is corrupted
     * 
     * @see #FingerprintTemplate(byte[])
     */
    @Deprecated public FingerprintTemplate deserialize(String json) {
        Objects.requireNonNull(json);
        PersistentTemplate persistent = new Gson().fromJson(json, PersistentTemplate.class);
        persistent.validate();
        inner = new SearchTemplate(persistent.mutable());
        return this;
    }
    /**
     * Serializes fingerprint template into byte array.
     * Serialized template can be stored in a database or sent over network.
     * It can be then deserialized by calling {@link #FingerprintTemplate(byte[])} constructor.
     * Persisting templates alongside fingerprint images allows applications to start faster,
     * because template deserialization is more than 100x faster than re-extraction from fingerprint image.
     * <p>
     * Serialized template excludes search structures that {@code FingerprintTemplate} keeps to speed up matching.
     * Serialized template is therefore much smaller than in-memory {@code FingerprintTemplate}.
     * <p>
     * Serialization format can change with every SourceAFIS version. There is no backward compatibility of templates.
     * Applications should preserve raw fingerprint images, so that templates can be re-extracted after SourceAFIS upgrade.
     * Template format for current version of SourceAFIS is
     * <a href="https://sourceafis.machinezoo.com/template">documented on SourceAFIS website</a>.
     * 
     * @return serialized fingerprint template in <a href="https://cbor.io/">CBOR</a> format
     * 
     * @see #FingerprintTemplate(byte[])
     * @see <a href="https://sourceafis.machinezoo.com/template">Template format</a>
     * @see FingerprintCompatibility#exportTemplates(TemplateFormat, FingerprintTemplate...)
     */
    public byte[] toByteArray() {
        PersistentTemplate persistent = new PersistentTemplate(inner.features());
        return Exceptions.wrap().get(() -> mapper.writeValueAsBytes(persistent));
    }
    /**
     * @deprecated Use {@link #toByteArray()} to serialize the template.
     * 
     * @return serialized fingerprint template in JSON format
     * 
     * @see #toByteArray()
     */
    @Deprecated public String serialize() { return new Gson().toJson(new PersistentTemplate(inner.features())); }
    /**
     * @deprecated Use {@link FingerprintCompatibility} methods to import other template formats.
     * 
     * @param template
     *            foreign template to import
     * @return {@code this} (fluent method)
     * 
     * @see FingerprintCompatibility#convert(byte[])
     */
    @Deprecated public FingerprintTemplate convert(byte[] template) {
        inner = FingerprintCompatibility.convert(template).inner;
        return this;
    }
    /**
     * Estimates memory footprint of the template.
     * Memory (RAM) footprint of templates is usually much larger than serialized size.
     * The estimate should be fairly accurate on all commonly used JVMs.
     * 
     * @return estimated memory footprint of the template in bytes
     */
    public int memory() { return MemoryEstimates.object(MemoryEstimates.REFERENCE + Double.BYTES, Double.BYTES) + inner.memory(); }
}
