package org.infinispan.commons.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.naming.Context;
import javax.security.auth.Subject;

import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.hash.Hash;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.Marshaller;

/**
 * General utility methods used throughout the Infinispan code base.
 *
 * @author <a href="brian.stansberry@jboss.com">Brian Stansberry</a>
 * @author Galder Zamarreño
 * @since 4.0
 */
public final class Util {

   private static final boolean IS_ARRAYS_DEBUG = Boolean.getBoolean("infinispan.arrays.debug");
   private static final int COLLECTIONS_LIMIT = Integer.getInteger("infinispan.collections.limit", 8);
   public static final int HEX_DUMP_LIMIT = Integer.getInteger("infinispan.hexdump.limit", 64);

   public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
   public static final String[] EMPTY_STRING_ARRAY = new String[0];
   public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
   public static final byte[][] EMPTY_BYTE_ARRAY_ARRAY = new byte[0][];
   public static final String GENERIC_JBOSS_MARSHALLING_CLASS = "org.infinispan.jboss.marshalling.commons.GenericJBossMarshaller";
   public static final String JBOSS_USER_MARSHALLER_CLASS = "org.infinispan.jboss.marshalling.core.JBossUserMarshaller";

   private static final Log log = LogFactory.getLog(Util.class);

   private static final Set<Class<?>> BASIC_TYPES;

   private static final String HEX_VALUES = "0123456789ABCDEF";
   private static final char[] HEX_DUMP_CHARS = new char[256*2];

   /**
    * Current Java vendor. This variable is later used to differentiate LRU implementations
    * for different java vendors.
    */
   private static final String javaVendor = SecurityActions.getProperty("java.vendor", "");

   static {
      BASIC_TYPES = new HashSet<>();
      BASIC_TYPES.add(Boolean.class);
      BASIC_TYPES.add(Byte.class);
      BASIC_TYPES.add(Character.class);
      BASIC_TYPES.add(Double.class);
      BASIC_TYPES.add(Float.class);
      BASIC_TYPES.add(Integer.class);
      BASIC_TYPES.add(Long.class);
      BASIC_TYPES.add(Short.class);
      BASIC_TYPES.add(String.class);

      for (char b = 0; b < 256; b++) {
         if (0x20 <= b && b <= 0x7e) {
            HEX_DUMP_CHARS[b * 2] = '\\';
            HEX_DUMP_CHARS[b * 2 + 1] = b;
         } else {
            HEX_DUMP_CHARS[b * 2] = HEX_VALUES.charAt((b & 0xF0) >> 4);
            HEX_DUMP_CHARS[b * 2 + 1] = HEX_VALUES.charAt((b & 0x0F));
         }

      }
   }

   /**
    * <p>
    * Loads the specified class using the passed classloader, or, if it is <code>null</code> the Infinispan classes'
    * classloader.
    * </p>
    *
    * <p>
    * If loadtime instrumentation via GenerateInstrumentedClassLoader is used, this class may be loaded by the bootstrap
    * classloader.
    * </p>
    * <p>
    * If the class is not found, the {@link ClassNotFoundException} or {@link NoClassDefFoundError} is wrapped as a
    * {@link CacheConfigurationException} and is re-thrown.
    * </p>
    *
    * @param classname name of the class to load
    * @param cl the application classloader which should be used to load the class, or null if the class is always packaged with
    *        Infinispan
    * @return the class
    * @throws CacheConfigurationException if the class cannot be loaded
    */
   public static <T> Class<T> loadClass(String classname, ClassLoader cl) {
      try {
         return loadClassStrict(classname, cl);
      } catch (ClassNotFoundException e) {
         throw new CacheConfigurationException("Unable to instantiate class " + classname, e);
      }
   }

   public static ClassLoader[] getClassLoaders(ClassLoader appClassLoader) {
      return SecurityActions.getClassLoaders(appClassLoader);
   }

   /**
    * <p>
    * Loads the specified class using the passed classloader, or, if it is <code>null</code> the Infinispan classes' classloader.
    * </p>
    *
    * <p>
    * If loadtime instrumentation via GenerateInstrumentedClassLoader is used, this class may be loaded by the bootstrap classloader.
    * </p>
    *
    * @param classname name of the class to load
    * @return the class
    * @param userClassLoader the application classloader which should be used to load the class, or null if the class is always packaged with
    *        Infinispan
    * @throws ClassNotFoundException if the class cannot be loaded
    */
   @SuppressWarnings("unchecked")
   public static <T> Class<T> loadClassStrict(String classname, ClassLoader userClassLoader) throws ClassNotFoundException {
      ClassLoader[] cls = getClassLoaders(userClassLoader);
         ClassNotFoundException e = null;
         NoClassDefFoundError ne = null;
         for (ClassLoader cl : cls)  {
            if (cl == null)
               continue;

            try {
               return (Class<T>) Class.forName(classname, true, cl);
            } catch (ClassNotFoundException ce) {
               e = ce;
            } catch (NoClassDefFoundError ce) {
               ne = ce;
            }
         }
         if (ne != null) {
            //Always log the NoClassDefFoundError errors first:
            //if one happened they will contain critically useful details.
            log.unableToLoadClass(classname, Arrays.toString(cls), ne);
         }
         if (e != null)
            throw e;
         else if (ne != null) {
            throw new ClassNotFoundException(classname, ne);
         }
         else
            throw new IllegalStateException();
   }

   public static InputStream getResourceAsStream(String resourcePath, ClassLoader userClassLoader) {
      if (resourcePath.startsWith("/")) {
         resourcePath = resourcePath.substring(1);
      }
      InputStream is = null;
      for (ClassLoader cl : getClassLoaders(userClassLoader)) {
         if (cl != null) {
            is = cl.getResourceAsStream(resourcePath);
            if (is != null) {
               break;
            }
         }
      }
      return is;
   }

   public static String getResourceAsString(String resourcePath, ClassLoader userClassLoader) throws IOException {
      return read(getResourceAsStream(resourcePath, userClassLoader));
   }

   private static Method getFactoryMethod(Class<?> c) {
      for (Method m : c.getMethods()) {
         if (m.getName().equals("getInstance") && m.getParameterTypes().length == 0 && Modifier.isStatic(m.getModifiers()))
            return m;
      }
      return null;
   }

   /**
    * Instantiates a class by invoking the constructor that matches the provided parameter types passing the given
    * arguments. If no matching constructor is found this will return null. Note that the constructor must be public.
    * <p/>
    * Any exceptions encountered are wrapped in a {@link CacheConfigurationException} and rethrown.
    * @param clazz class to instantiate
    * @param <T> the instance type
    * @return the new instance if a matching constructor was found otherwise null
    */
   public static <T> T newInstanceOrNull(Class<T> clazz, Class[] parameterTypes, Object... arguments) {
      if (parameterTypes.length != arguments.length) {
         throw new IllegalArgumentException("Parameter type count: " + parameterTypes.length +
               " does not match parameter arguments count: " + arguments.length);
      }
      try {
         Constructor<T> constructor = clazz.getDeclaredConstructor(parameterTypes);

         if (constructor != null) {
            return constructor.newInstance(arguments);
         }

      } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
         throw new CacheConfigurationException("Unable to instantiate class " + clazz.getName() + " with constructor " +
               "taking parameters " + Arrays.toString(arguments), e);
      }
      return null;
   }

   /**
    * Instantiates a class by first attempting a static <i>factory method</i> named <tt>getInstance()</tt> on the class
    * and then falling back to an empty constructor.
    * <p/>
    * Any exceptions encountered are wrapped in a {@link CacheConfigurationException} and rethrown.
    *
    * @param clazz class to instantiate
    * @return an instance of the class
    */
   public static <T> T getInstance(Class<T> clazz) {
      try {
         return getInstanceStrict(clazz);
      } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException iae) {
         throw new CacheConfigurationException("Unable to instantiate class " + clazz.getName(), iae);
      }
   }

   /**
    * Similar to {@link #getInstance(Class)} except that exceptions are propagated to the caller.
    *
    * @param clazz class to instantiate
    * @return an instance of the class
    * @throws IllegalAccessException
    * @throws InstantiationException
    */
   @SuppressWarnings("unchecked")
   public static <T> T getInstanceStrict(Class<T> clazz) throws IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
      // first look for a getInstance() constructor
      T instance = null;
      try {
         Method factoryMethod = getFactoryMethod(clazz);
         if (factoryMethod != null) instance = (T) factoryMethod.invoke(null);
      }
      catch (Exception e) {
         // no factory method or factory method failed.  Try a constructor.
         instance = null;
      }
      if (instance == null) {
         instance = clazz.getDeclaredConstructor().newInstance();
      }
      return instance;
   }

   /**
    * Instantiates a class based on the class name provided.  Instantiation is attempted via an appropriate, static
    * factory method named <tt>getInstance()</tt> first, and failing the existence of an appropriate factory, falls
    * back to an empty constructor.
    * <p />
    * Any exceptions encountered loading and instantiating the class is wrapped in a {@link CacheConfigurationException}.
    *
    * @param classname class to instantiate
    * @return an instance of classname
    */
   public static <T> T getInstance(String classname, ClassLoader cl) {
      if (classname == null) throw new IllegalArgumentException("Cannot load null class!");
      Class<T> clazz = loadClass(classname, cl);
      return getInstance(clazz);
   }

   /**
    * Similar to {@link #getInstance(String, ClassLoader)} except that exceptions are propagated to the caller.
    *
    * @param classname class to instantiate
    * @return an instance of classname
    * @throws ClassNotFoundException
    * @throws InstantiationException
    * @throws IllegalAccessException
    * @throws NoSuchMethodException
    * @throws InvocationTargetException
    */
   public static <T> T getInstanceStrict(String classname, ClassLoader cl) throws ClassNotFoundException, InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
      if (classname == null) throw new IllegalArgumentException("Cannot load null class!");
      Class<T> clazz = loadClassStrict(classname, cl);
      return getInstanceStrict(clazz);
   }

   /**
    * Clones parameter x of type T with a given Marshaller reference;
    *
    *
    * @return a deep clone of an object parameter x
    */
   @SuppressWarnings("unchecked")
   public static <T> T cloneWithMarshaller(Marshaller marshaller, T x){
      if (marshaller == null)
         throw new IllegalArgumentException("Cannot use null Marshaller for clone");

      byte[] byteBuffer;
      try {
         byteBuffer = marshaller.objectToByteBuffer(x);
         return (T) marshaller.objectFromByteBuffer(byteBuffer);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new CacheException(e);
      } catch (Exception e) {
         throw new CacheException(e);
      }
   }

   /**
    * Given two Runnables, return a Runnable that executes both in sequence,
    * even if the first throws an exception, and if both throw exceptions, add
    * any exceptions thrown by the second as suppressed exceptions of the first.
    */
   public static Runnable composeWithExceptions(Runnable a, Runnable b) {
      return () -> {
         try {
            a.run();
         }
         catch (Throwable e1) {
            try {
               b.run();
            }
            catch (Throwable e2) {
               try {
                  e1.addSuppressed(e2);
               } catch (Throwable ignore) {}
            }
            throw e1;
         }
         b.run();
      };
   }


   /**
    * Prevent instantiation
    */
   private Util() {
   }

   /**
    * Null-safe equality test.
    *
    * @param a first object to compare
    * @param b second object to compare
    * @return true if the objects are equals or both null, false otherwise.
    */
   public static boolean safeEquals(Object a, Object b) {
      return (a == b) || (a != null && a.equals(b));
   }

   public static String prettyPrintTime(long time, TimeUnit unit) {
      return prettyPrintTime(unit.toMillis(time));
   }

   /**
    * {@link System#nanoTime()} is less expensive than {@link System#currentTimeMillis()} and better suited
    * to measure time intervals. It's NOT suited to know the current time, for example to be compared
    * with the time of other nodes.
    * @return the value of {@link System#nanoTime()}, but converted in Milliseconds.
    */
   public static long currentMillisFromNanotime() {
      return TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
   }

   /**
    * Prints a time for display
    *
    * @param millis time in millis
    * @return the time, represented as millis, seconds, minutes or hours as appropriate, with suffix
    */
   public static String prettyPrintTime(long millis) {
      if (millis < 1000) return millis + " milliseconds";
      NumberFormat nf = NumberFormat.getNumberInstance();
      nf.setMaximumFractionDigits(2);
      double toPrint = ((double) millis) / 1000;
      if (toPrint < 300) {
         return nf.format(toPrint) + " seconds";
      }

      toPrint = toPrint / 60;

      if (toPrint < 120) {
         return nf.format(toPrint) + " minutes";
      }

      toPrint = toPrint / 60;

      return nf.format(toPrint) + " hours";
   }

   /**
    * Reads the given InputStream fully, closes the stream and returns the result as a byte array.
    *
    * @param is the stream to read
    * @return the read bytes
    * @throws java.io.IOException in case of stream read errors
    */
   public static byte[] readStream(InputStream is) throws IOException {
      try {
         ByteArrayOutputStream os = new ByteArrayOutputStream();
         byte[] buf = new byte[1024];
         int len;
         while ((len = is.read(buf)) != -1) {
            os.write(buf, 0, len);
         }
         return os.toByteArray();
      } finally {
         is.close();
      }
   }

    /**
     * Reads the given InputStream fully, closes the stream and returns the result as a String.
     *
     * @param is the stream to read
     * @return the UTF-8 string
     * @throws java.io.IOException in case of stream read errors
     */
    public static String read(InputStream is) throws IOException {
       try {
          final Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
          StringWriter writer = new StringWriter();
          char[] buf = new char[1024];
          int len;
          while ((len = reader.read(buf)) != -1) {
             writer.write(buf, 0, len);
          }
          return writer.toString();
       } finally {
          is.close();
       }
    }

   public static void close(AutoCloseable cl) {
      if (cl == null) return;
      try {
         cl.close();
      } catch (Exception e) {
      }
   }

   public static void close(Socket s) {
      if (s == null) return;
      try {
         s.close();
      } catch (Exception e) {
      }
   }

   public static void close(AutoCloseable... cls) {
      for (AutoCloseable cl : cls) {
         close(cl);
      }
   }

   public static void close(Context ctx) {
      if (ctx == null) return;
      try {
         ctx.close();
      } catch (Exception e) {
      }
   }

   public static void flushAndCloseStream(OutputStream o) {
      if (o == null) return;
      try {
         o.flush();
      } catch (Exception e) {

      }

      try {
         o.close();
      } catch (Exception e) {

      }
   }

   public static void flushAndCloseOutput(ObjectOutput o) {
      if (o == null) return;
      try {
         o.flush();
      } catch (Exception e) {

      }

      try {
         o.close();
      } catch (Exception e) {

      }
   }

   public static String formatString(Object message, Object... params) {
      if (params.length == 0) return message == null ? "null" : message.toString();

      return String.format(message.toString(), params);
   }

   public static String toStr(Object o) {
      if (o == null) {
         return "null";
      } else if (o.getClass().isArray()) {
         // as Java arrays are covariant, this cast is safe unless it's primitive
         if (o.getClass().getComponentType().isPrimitive()) {
            if (o instanceof byte[]) {
               return printArray((byte[]) o, false);
            } else if (o instanceof int[]) {
               return Arrays.toString((int[]) o);
            } else if (o instanceof long[]) {
               return Arrays.toString((long[]) o);
            } else if (o instanceof short[]) {
               return Arrays.toString((short[]) o);
            } else if (o instanceof double[]) {
               return Arrays.toString((double[]) o);
            } else if (o instanceof float[]) {
               return Arrays.toString((float[]) o);
            } else if (o instanceof char[]) {
               return Arrays.toString((char[]) o);
            } else if (o instanceof boolean[]) {
               return Arrays.toString((boolean[]) o);
            }
         }
         return Arrays.toString((Object[]) o);
      } else {
         return o.toString();
      }
   }

   public static <E> String toStr(Collection<E> collection) {
      if (collection == null)
         return "[]";

      Iterator<E> i = collection.iterator();
      if (!i.hasNext())
         return "[]";

      StringBuilder sb = new StringBuilder();
      sb.append('[');
      for (int counter = 0;;) {
         E e = i.next();
         sb.append(e == collection ? "(this Collection)" : toStr(e));
         if (! i.hasNext())
            return sb.append(']').toString();
         if (++counter >= COLLECTIONS_LIMIT) {
            return sb.append("...<")
                  .append(collection.size() - COLLECTIONS_LIMIT)
                  .append(" other elements>]").toString();
         }
         sb.append(", ");
      }
   }

   public static String printArray(byte[] array) {
      return printArray(array, false);
   }

   public static String printArray(byte[] array, boolean withHash) {
      if (array == null) return "null";

      int limit = 16;
      StringBuilder sb = new StringBuilder();
      sb.append("[B0x");
      if (array.length <= limit || IS_ARRAYS_DEBUG) {
         // Convert the entire byte array
         sb.append(toHexString(array));
         if (withHash) {
            sb.append(",h=");
            sb.append(Integer.toHexString(Arrays.hashCode(array)));
            sb.append(']');
         }
      } else {
         // Pick the first limit characters and convert that part
         sb.append(toHexString(array, limit));
         sb.append("..[");
         sb.append(array.length);
         if (withHash) {
            sb.append("],h=");
            sb.append(Integer.toHexString(Arrays.hashCode(array)));
         }
         sb.append(']');
      }
      return sb.toString();
   }

   public static String toHexString(byte[] input) {
      return toHexString(input, input != null ? input.length : 0);
   }

   public static String toHexString(byte[] input, int limit) {
      return toHexString(input, 0, limit);
   }

   public static String toHexString(byte[] input, int offset, int limit) {
      if (input == null)
         return "null";

      int length = Math.min(limit - offset, input.length - offset);
      char[] result = new char[length * 2];

      for (int i = 0; i < length; ++i) {
         result[2*i] = HEX_VALUES.charAt((input[i + offset] >> 4) & 0x0F);
         result[2*i+1] = HEX_VALUES.charAt((input[i + offset] & 0x0F));
      }
      return String.valueOf(result);
   }

   public static String padString(String s, int minWidth) {
      if (s.length() < minWidth) {
         StringBuilder sb = new StringBuilder(s);
         while (sb.length() < minWidth) sb.append(" ");
         return sb.toString();
      }
      return s;
   }

   private final static String INDENT = "    ";

   public static String threadDump() {
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      String timestamp = dateFormat.format(new Date());

      StringBuilder threadDump = new StringBuilder();
      threadDump.append(timestamp);
      threadDump.append("\nFull thread dump ");
      threadDump.append("\n");

      Thread.getAllStackTraces().forEach((thread, elements) -> {
         threadDump.append("\"").append(thread.getName())
               .append("\" nid=").append(thread.getId())
               .append(" state=").append(thread.getState())
               .append("\n");

         for (StackTraceElement e : elements) {
            threadDump.append(INDENT).append("at ").append(e.toString()).append("\n");
         }
      });
      return threadDump.toString();
   }

   public static CacheException rewrapAsCacheException(Throwable t) {
      if (t instanceof CacheException)
         return (CacheException) t;
      else
         return new CacheException(t);
   }

   @SafeVarargs
   public static <T> Set<T> asSet(T... a) {
      if (a.length > 1)
         return new HashSet<>(Arrays.asList(a));
      else
         return Collections.singleton(a[0]);
   }

   /**
    * Prints the identity hash code of the object passed as parameter
    * in an hexadecimal format in order to safe space.
    */
   public static String hexIdHashCode(Object o) {
      return Integer.toHexString(System.identityHashCode(o));
   }

   public static String hexDump(byte[] data) {
      return hexDump(data, data.length);
   }

   public static String hexDump(ByteBuffer buffer) {
      int bufferLength = buffer.remaining();
      int dumpLength = Math.min(bufferLength, HEX_DUMP_LIMIT);
      byte[] data = new byte[dumpLength];
      int pos = buffer.position();
      buffer.get(data);
      buffer.position(pos);
      return hexDump(data, bufferLength);
   }

   public static String hexDump(byte[] buffer, int actualLength) {
      StringBuilder sb = new StringBuilder(buffer.length * 2 + 30);
      for (byte b : buffer) {
         addHexByte(sb, b);
      }
      if (buffer.length <= actualLength) {
         sb.append("...");
      }
      sb.append(" (").append(actualLength).append(" bytes)");
      return sb.toString();
   }

   public static void addHexByte(StringBuilder buf, byte b) {
      int offset = (b & 0xFF) * 2;
      buf.append(HEX_DUMP_CHARS, offset, 2);
   }

   private static void addSingleHexChar(StringBuilder buf, byte b) {
      buf.append(HEX_VALUES.charAt(b & 0x0F));
   }

   public static Double constructDouble(Class<?> type, Object o) {
      if (type.equals(Long.class) || type.equals(long.class))
         return Double.valueOf((Long) o);
      else if (type.equals(Double.class) || type.equals(double.class))
         return (Double) o;
      else if (type.equals(Integer.class) || type.equals(int.class))
         return Double.valueOf((Integer) o);
      else if (type.equals(String.class))
         return Double.valueOf((String) o);

      throw new IllegalStateException(String.format("Expected a value that can be converted into a double: type=%s, value=%s", type, o));
   }

   /**
    * Applies the given hash function to the hash code of a given object, and then normalizes it to ensure a positive
    * value is always returned.
    * @param object to hash
    * @param hashFct hash function to apply
    * @return a non-null, non-negative normalized hash code for a given object
    */
   public static int getNormalizedHash(Object object, Hash hashFct) {
      // make sure no negative numbers are involved.
      return hashFct.hash(object) & Integer.MAX_VALUE;
   }

   /**
    * Returns the size of each segment, given a number of segments.
    * @param numSegments number of segments required
    * @return the size of each segment
    */
   public static int getSegmentSize(int numSegments) {
      return (int)Math.ceil((double)(1L << 31) / numSegments);
   }

   public static boolean isIBMJavaVendor() {
      return javaVendor.toLowerCase().contains("ibm");
   }

   public static String join(List<String> strings, String separator) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;

      for (String string : strings) {
         if (!first) {
            sb.append(separator);
         } else {
            first = false;
         }
         sb.append(string);
      }

      return sb.toString();
   }

   /**
    * Returns a number such that the number is a power of two that is equal to, or greater than, the number passed in as
    * an argument.  The smallest number returned will be 1. Due to having to be a power of two, the highest int
    * this can return is 2<sup>31 since int is signed.
    */
   public static int findNextHighestPowerOfTwo(int num) {
      if (num <= 1) {
         return 1;
      } else if (num >= 0x40000000) {
         return 0x40000000;
      }
      int highestBit = Integer.highestOneBit(num);
      return num <= highestBit ? highestBit : highestBit << 1;
   }

   /**
    * A function that calculates hash code of a byte array based on its
    * contents but using the given size parameter as deliminator for the
    * content.
    */
   public static int hashCode(byte[] bytes, int size) {
      int contentLimit = size;
      if (size > bytes.length)
         contentLimit = bytes.length;

      int hashCode = 1;
      for (int i = 0; i < contentLimit; i++)
         hashCode = 31 * hashCode + bytes[i];

      return hashCode;
   }

   /**
    *
    * Prints {@link Subject}'s principals as a one-liner
    * (as opposed to default Subject's <code>toString()</code> method, which prints every principal on separate line).
    *
    */
   public static String prettyPrintSubject(Subject subject) {
      return (subject == null) ? "null" : "Subject with principal(s): " + toStr(subject.getPrincipals());
   }

   /**
    * Concatenates an arbitrary number of arrays returning a new array containing all elements
    */
   @SafeVarargs
   public static <T> T[] arrayConcat(T[] first, T[]... rest) {
      int totalLength = first.length;
      for (T[] array : rest) {
        totalLength += array.length;
      }
      T[] result = Arrays.copyOf(first, totalLength);
      int offset = first.length;
      for (T[] array : rest) {
        System.arraycopy(array, 0, result, offset, array.length);
        offset += array.length;
      }
      return result;
   }

   /**
    * Uses a {@link ThreadLocalRandom} to generate a UUID. Faster, but not secure
    */
   public static UUID threadLocalRandomUUID() {
      byte[] data = new byte[16];
      ThreadLocalRandom.current().nextBytes(data);
      data[6] &= 0x0f; /* clear version */
      data[6] |= 0x40; /* set to version 4 */
      data[8] &= 0x3f; /* clear variant */
      data[8] |= 0x80; /* set to IETF variant */
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++)
         msb = (msb << 8) | (data[i] & 0xff);
      for (int i = 8; i < 16; i++)
         lsb = (lsb << 8) | (data[i] & 0xff);
      return new UUID(msb, lsb);
   }

   public static String unicodeEscapeString(String s) {
      int len = s.length();
      StringBuilder out = new StringBuilder(len * 2);

      for (int x = 0; x < len; x++) {
         char aChar = s.charAt(x);
         if ((aChar > 61) && (aChar < 127)) {
            if (aChar == '\\') {
               out.append('\\');
               out.append('\\');
               continue;
            }
            out.append(aChar);
            continue;
         }
         switch (aChar) {
         case ' ':
            if (x == 0)
               out.append('\\');
            out.append(' ');
            break;
         case '\t':
            out.append('\\');
            out.append('t');
            break;
         case '\n':
            out.append('\\');
            out.append('n');
            break;
         case '\r':
            out.append('\\');
            out.append('r');
            break;
         case '\f':
            out.append('\\');
            out.append('f');
            break;
         case '=':
         case ':':
         case '#':
         case '!':
            out.append('\\');
            out.append(aChar);
            break;
         default:
            if ((aChar < 0x0020) || (aChar > 0x007e)) {
               out.append('\\');
               out.append('u');
               addSingleHexChar(out, (byte)((aChar >> 12) & 0xF));
               addSingleHexChar(out, (byte)((aChar >> 8) & 0xF));
               addSingleHexChar(out, (byte)((aChar >> 4) & 0xF));
               addSingleHexChar(out, (byte)(aChar & 0xF));
            } else {
               out.append(aChar);
            }
         }
      }
      return out.toString();
   }

   public static String unicodeUnescapeString(String s) {
      int len = s.length();
      StringBuilder out = new StringBuilder(len);

      for (int x = 0; x < len; x++) {
         char ch = s.charAt(x);
         if (ch == '\\') {
            ch = s.charAt(++x);
            if (ch == 'u') {
               int value = 0;
               for (int i = 0; i < 4; i++) {
                  ch = s.charAt(++x);
                  if (ch >= '0' && ch <= '9') {
                     value = (value << 4) + ch - '0';
                  } else if (ch >= 'a' && ch <= 'f') {
                     value = (value << 4) + 10 + ch - 'a';
                  } else if (ch >= 'A' && ch <= 'F') {
                     value = (value << 4) + 10 + ch - 'A';
                  } else throw new IllegalArgumentException("Malformed \\uxxxx encoding.");
               }
               out.append((char) value);
            } else {
               if (ch == 't')
                  ch = '\t';
               else if (ch == 'r')
                  ch = '\r';
               else if (ch == 'n')
                  ch = '\n';
               else if (ch == 'f')
                  ch = '\f';
               out.append(ch);
            }
         } else {
            out.append(ch);
         }
      }
      return out.toString();
   }

   public static <T> Supplier<T> getInstanceSupplier(Class<T> klass) {
      return () -> getInstance(klass);
   }

   public static <T> Supplier<T> getInstanceSupplier(String className, ClassLoader classLoader) {
      return () -> getInstance(className, classLoader);
   }

  /**
    * Deletes directory recursively.
    *
    * @param directoryName Directory to be deleted
    */
   public static void recursiveFileRemove(String directoryName) {
      File file = new File(directoryName);
      recursiveFileRemove(file);
   }

   public static void recursiveFileRemove(Path path) {
      recursiveFileRemove(path.toFile());
   }

   /**
    * Deletes directory recursively.
    *
    * @param directory Directory to be deleted
    */
   public static void recursiveFileRemove(File directory) {
      if (directory.exists()) {
         log.tracef("Deleting file %s", directory);
         recursiveDelete(directory);
      }
   }

   private static void recursiveDelete(File f) {
      try {
         Files.walk(f.toPath())
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   public static void recursiveDirectoryCopy(Path source, Path target) throws IOException {
      Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new FileVisitor<Path>() {
         @Override
         public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            try {
               if (!source.equals(dir)) {
                  String relativize = source.relativize(dir).toString();
                  Path resolve = target.resolve(relativize);
                  Files.copy(dir, resolve);
               }
            } catch (FileAlreadyExistsException x) {
               // do nothing
            } catch (IOException x) {
               return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.copy(file, target.resolve(source.relativize(file).toString()));
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
         }
      });
   }

   public static boolean isBasicType(Class<?> type) {
      return BASIC_TYPES.contains(type);
   }

   public static String xmlify(String s) {
      StringBuilder sb = new StringBuilder();
      for(int i=0; i < s.length(); i++) {
         char ch = s.charAt(i);
         if (Character.isUpperCase(ch)) {
            sb.append('-');
            sb.append(Character.toLowerCase(ch));
         } else {
            sb.append(ch);
         }
      }
      return sb.toString();
   }

   public static char[] toCharArray(String s) {
      return s == null ? null : s.toCharArray();
   }

   public static Object[] objectArray(int length) {
      return length == 0 ? EMPTY_OBJECT_ARRAY : new Object[length];
   }

   public static String[] stringArray(int length) {
      return length == 0 ? EMPTY_STRING_ARRAY : new String[length];
   }

   public static void renameTempFile(File tempFile, File lockFile, File dstFile)
         throws IOException {
      FileLock lock = null;
      try (FileOutputStream lockFileOS = new FileOutputStream(lockFile)) {
         lock = lockFileOS.getChannel().lock();
         Files.move(tempFile.toPath(), dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      } finally {
         if (lock != null && lock.isValid()) {
            lock.release();
         }
         if (!lockFile.delete()) {
            log.debugf("Unable to delete lock file %s", lockFile);
         }
      }
   }

   public static Throwable getRootCause(Throwable re) {
      if (re == null) return null;
      Throwable cause = re.getCause();
      if (cause != null)
         return getRootCause(cause);
      else
         return re;
   }

   public static Marshaller getJBossMarshaller(ClassLoader classLoader, ClassAllowList classAllowList) {
      try {
         Class<?> marshallerClass = classLoader.loadClass(GENERIC_JBOSS_MARSHALLING_CLASS);
         return Util.newInstanceOrNull(marshallerClass.asSubclass(Marshaller.class),
               new Class[]{ClassLoader.class, ClassAllowList.class}, classLoader, classAllowList);
      } catch (ClassNotFoundException e) {
         return null;
      }
   }

   // TODO: Replace with Objects.requireNonNullElse(T obj, T defaultObj) when upgrading to JDK 9+
   public static <T> T requireNonNullElse(T obj, T defaultObj) {
      return (obj != null) ? obj : Objects.requireNonNull(defaultObj, "defaultObj");
   }

   public static void longToBytes(long val, byte[] array, int offset) {
      for (int i = 7; i > 0; i--) {
         array[offset + i] = (byte) val;
         val >>>= 8;
      }
      array[offset] = (byte) val;
   }

   public static String unquote(String s) {
      if (s.charAt(0) == '"' || s.charAt(0) == '\'') {
         return s.substring(1, s.length() - 1);
      } else {
         return s;
      }
   }

}
