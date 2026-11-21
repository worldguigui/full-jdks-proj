# Frontier Chronicle · API 使用审计报告

> 生成日期：2026-11-21 ｜ 数据口径：阶段一冻结清单（分母 3836 类型 / 176 包 / 20 模块，Preview 不计入）

## 总览

| 指标 | 已使用 | 分母 | 覆盖率 |
|---|---:|---:|---:|
| 类型 | 350 | 3836 | 9.1% |
| 包 | 57 | 176 | 32.4% |
| 模块 | 9 | 20 | 45.0% |

扫描 class 文件 293 个；Preview 违规 0 项。

## 模块明细

| 模块 | 已使用 | 分母 | 覆盖率 |
|---|---:|---:|---:|
| java.base | 204 | 1353 | 15.1% |
| java.compiler | 15 | 136 | 11.0% |
| java.datatransfer | 0 | 12 | 0.0% |
| java.desktop | 66 | 1524 | 4.3% |
| java.instrument | 0 | 6 | 0.0% |
| java.logging | 1 | 17 | 5.9% |
| java.management | 5 | 195 | 2.6% |
| java.management.rmi | 0 | 9 | 0.0% |
| java.naming | 0 | 102 | 0.0% |
| java.net.http | 9 | 22 | 40.9% |
| java.prefs | 2 | 9 | 22.2% |
| java.rmi | 0 | 55 | 0.0% |
| java.scripting | 0 | 12 | 0.0% |
| java.security.jgss | 0 | 16 | 0.0% |
| java.security.sasl | 0 | 10 | 0.0% |
| java.sql | 16 | 74 | 21.6% |
| java.sql.rowset | 0 | 30 | 0.0% |
| java.transaction.xa | 0 | 3 | 0.0% |
| java.xml | 32 | 193 | 16.6% |
| java.xml.crypto | 0 | 58 | 0.0% |

## 预览违规

无。核心模块未引用 Preview 类型，也未产出带预览标记的 class。

## 未覆盖类型最多的包（前 20）

| 包 | 未使用 | 分母 |
|---|---:|---:|
| javax.swing | 171 | 171 |
| javax.swing.plaf.basic | 151 | 151 |
| java.awt | 116 | 132 |
| javax.swing.text | 115 | 115 |
| java.util | 98 | 134 |
| java.security | 87 | 93 |
| java.lang | 85 | 124 |
| javax.print.attribute.standard | 76 | 76 |
| javax.management | 69 | 73 |
| java.io | 64 | 86 |
| java.util.concurrent | 62 | 73 |
| javax.swing.plaf.metal | 61 | 61 |
| java.net | 60 | 66 |
| java.nio.channels | 55 | 56 |
| javax.swing.plaf.synth | 51 | 51 |
| java.security.cert | 49 | 52 |
| javax.lang.model.util | 49 | 49 |
| javax.swing.event | 49 | 49 |
| javax.swing.plaf | 49 | 49 |
| java.awt.image | 47 | 55 |

## 使用到的类型（按模块）

- **java.base**（204）：java.io.BufferedInputStream、java.io.BufferedOutputStream、java.io.BufferedReader、java.io.BufferedWriter、java.io.ByteArrayInputStream、java.io.ByteArrayOutputStream、java.io.DataInputStream、java.io.DataOutputStream、java.io.File、java.io.FileDescriptor、java.io.FileOutputStream、java.io.IOException、java.io.InputStream、java.io.InputStreamReader、java.io.OutputStream、java.io.OutputStreamWriter、java.io.PrintStream、java.io.PrintWriter、java.io.Reader、java.io.StringReader、java.io.StringWriter、java.io.Writer、java.lang.AutoCloseable、java.lang.Boolean、java.lang.Byte、java.lang.CharSequence、java.lang.Character、java.lang.Class、java.lang.ClassLoader、java.lang.Comparable、java.lang.Double、java.lang.Enum、java.lang.Exception、java.lang.ExceptionInInitializerError、java.lang.Float、java.lang.FunctionalInterface、java.lang.IllegalArgumentException、java.lang.IllegalStateException、java.lang.Integer、java.lang.InterruptedException、java.lang.Iterable、java.lang.LinkageError、java.lang.Long、java.lang.MatchException、java.lang.Math、java.lang.NoSuchFieldError、java.lang.Number、java.lang.NumberFormatException、java.lang.Object、java.lang.Record、java.lang.ReflectiveOperationException、java.lang.Runnable、java.lang.RuntimeException、java.lang.SecurityException、java.lang.Short、java.lang.String、java.lang.StringBuilder、java.lang.System、java.lang.Thread、java.lang.Throwable、java.lang.UnsupportedOperationException、java.lang.annotation.Annotation、java.lang.annotation.Documented、java.lang.annotation.ElementType、java.lang.annotation.Retention、java.lang.annotation.RetentionPolicy、java.lang.annotation.Target、java.lang.invoke.CallSite、java.lang.invoke.LambdaMetafactory、java.lang.invoke.MethodHandle、java.lang.invoke.MethodHandles、java.lang.invoke.MethodHandles$Lookup、java.lang.invoke.MethodType、java.lang.invoke.StringConcatFactory、java.lang.invoke.TypeDescriptor、java.lang.reflect.Constructor、java.lang.reflect.InvocationHandler、java.lang.reflect.InvocationTargetException、java.lang.reflect.Method、java.lang.reflect.Modifier、java.lang.reflect.Proxy、java.lang.runtime.ObjectMethods、java.lang.runtime.SwitchBootstraps、java.math.BigDecimal、java.math.BigInteger、java.math.MathContext、java.math.RoundingMode、java.net.InetAddress、java.net.InetSocketAddress、java.net.MalformedURLException、java.net.URI、java.net.URL、java.net.URLClassLoader、java.nio.channels.FileChannel、java.nio.charset.Charset、java.nio.charset.StandardCharsets、java.nio.file.AtomicMoveNotSupportedException、java.nio.file.CopyOption、java.nio.file.FileVisitOption、java.nio.file.Files、java.nio.file.LinkOption、java.nio.file.OpenOption、java.nio.file.Path、java.nio.file.StandardCopyOption、java.nio.file.StandardOpenOption、java.nio.file.attribute.FileAttribute、java.security.CodeSigner、java.security.GeneralSecurityException、java.security.Key、java.security.MessageDigest、java.security.NoSuchAlgorithmException、java.security.SecureRandom、java.security.cert.CertPath、java.security.cert.Certificate、java.security.cert.X509Certificate、java.security.spec.AlgorithmParameterSpec、java.security.spec.KeySpec、java.text.AttributedCharacterIterator、java.text.AttributedCharacterIterator$Attribute、java.text.AttributedString、java.text.MessageFormat、java.time.Duration、java.time.LocalDate、java.util.ArrayDeque、java.util.ArrayList、java.util.Arrays、java.util.Base64、java.util.Base64$Decoder、java.util.Base64$Encoder、java.util.Collection、java.util.Collections、java.util.Comparator、java.util.Deque、java.util.EnumMap、java.util.Enumeration、java.util.HashMap、java.util.HashSet、java.util.HexFormat、java.util.Iterator、java.util.LinkedHashMap、java.util.LinkedHashSet、java.util.List、java.util.Locale、java.util.Map、java.util.Map$Entry、java.util.MissingResourceException、java.util.Objects、java.util.Optional、java.util.OptionalInt、java.util.PriorityQueue、java.util.Properties、java.util.Random、java.util.ResourceBundle、java.util.ResourceBundle$Control、java.util.Set、java.util.SortedSet、java.util.SplittableRandom、java.util.TreeMap、java.util.TreeSet、java.util.concurrent.Callable、java.util.concurrent.ConcurrentHashMap、java.util.concurrent.ExecutionException、java.util.concurrent.Executor、java.util.concurrent.ExecutorService、java.util.concurrent.Executors、java.util.concurrent.Flow、java.util.concurrent.Flow$Subscriber、java.util.concurrent.Flow$Subscription、java.util.concurrent.Future、java.util.concurrent.SubmissionPublisher、java.util.concurrent.atomic.AtomicInteger、java.util.concurrent.atomic.AtomicLong、java.util.concurrent.atomic.AtomicReference、java.util.function.BiConsumer、java.util.function.BiFunction、java.util.function.Consumer、java.util.function.Function、java.util.function.Predicate、java.util.function.Supplier、java.util.function.ToIntFunction、java.util.function.ToLongFunction、java.util.jar.Attributes、java.util.jar.JarEntry、java.util.jar.JarFile、java.util.jar.JarOutputStream、java.util.jar.Manifest、java.util.random.RandomGenerator、java.util.random.RandomGeneratorFactory、java.util.regex.Matcher、java.util.regex.Pattern、java.util.stream.Collector、java.util.stream.Collectors、java.util.stream.IntStream、java.util.stream.Stream、java.util.zip.ZipEntry、java.util.zip.ZipException、java.util.zip.ZipOutputStream、javax.crypto.Cipher、javax.crypto.SecretKey、javax.crypto.SecretKeyFactory、javax.crypto.spec.GCMParameterSpec、javax.crypto.spec.PBEKeySpec、javax.crypto.spec.SecretKeySpec、javax.security.auth.x500.X500Principal
- **java.compiler**（15）：javax.tools.Diagnostic、javax.tools.Diagnostic$Kind、javax.tools.DiagnosticCollector、javax.tools.DiagnosticListener、javax.tools.FileObject、javax.tools.ForwardingJavaFileManager、javax.tools.JavaCompiler、javax.tools.JavaCompiler$CompilationTask、javax.tools.JavaFileManager、javax.tools.JavaFileManager$Location、javax.tools.JavaFileObject、javax.tools.JavaFileObject$Kind、javax.tools.SimpleJavaFileObject、javax.tools.StandardJavaFileManager、javax.tools.ToolProvider
- **java.desktop**（66）：java.awt.AlphaComposite、java.awt.BasicStroke、java.awt.Color、java.awt.Composite、java.awt.Font、java.awt.FontMetrics、java.awt.GradientPaint、java.awt.Graphics2D、java.awt.Image、java.awt.Paint、java.awt.Polygon、java.awt.Rectangle、java.awt.RenderingHints、java.awt.RenderingHints$Key、java.awt.Shape、java.awt.Stroke、java.awt.color.ColorSpace、java.awt.font.FontRenderContext、java.awt.font.GlyphVector、java.awt.font.TextAttribute、java.awt.font.TextLayout、java.awt.geom.AffineTransform、java.awt.geom.Area、java.awt.geom.CubicCurve2D、java.awt.geom.CubicCurve2D$Double、java.awt.geom.Ellipse2D、java.awt.geom.Ellipse2D$Double、java.awt.geom.Line2D、java.awt.geom.Line2D$Double、java.awt.geom.Path2D、java.awt.geom.Path2D$Double、java.awt.geom.Point2D、java.awt.geom.Point2D$Double、java.awt.geom.QuadCurve2D、java.awt.geom.QuadCurve2D$Double、java.awt.geom.Rectangle2D、java.awt.geom.Rectangle2D$Double、java.awt.geom.RoundRectangle2D、java.awt.geom.RoundRectangle2D$Double、java.awt.image.AffineTransformOp、java.awt.image.BufferedImage、java.awt.image.ColorConvertOp、java.awt.image.ConvolveOp、java.awt.image.ImageObserver、java.awt.image.Kernel、java.awt.image.RenderedImage、java.awt.image.RescaleOp、javax.imageio.IIOImage、javax.imageio.ImageIO、javax.imageio.ImageReadParam、javax.imageio.ImageReader、javax.imageio.ImageTypeSpecifier、javax.imageio.ImageWriteParam、javax.imageio.ImageWriter、javax.imageio.metadata.IIOMetadata、javax.imageio.metadata.IIOMetadataNode、javax.imageio.stream.ImageInputStream、javax.imageio.stream.ImageOutputStream、javax.sound.sampled.AudioFormat、javax.sound.sampled.AudioSystem、javax.sound.sampled.DataLine、javax.sound.sampled.DataLine$Info、javax.sound.sampled.Line、javax.sound.sampled.Line$Info、javax.sound.sampled.LineUnavailableException、javax.sound.sampled.SourceDataLine
- **java.logging**（1）：java.util.logging.Logger
- **java.management**（5）：java.lang.management.ManagementFactory、javax.management.JMException、javax.management.MBeanServer、javax.management.ObjectInstance、javax.management.ObjectName
- **java.net.http**（9）：java.net.http.HttpClient、java.net.http.HttpClient$Builder、java.net.http.HttpClient$Redirect、java.net.http.HttpClient$Version、java.net.http.HttpRequest、java.net.http.HttpRequest$Builder、java.net.http.HttpResponse、java.net.http.HttpResponse$BodyHandler、java.net.http.HttpResponse$BodyHandlers
- **java.prefs**（2）：java.util.prefs.BackingStoreException、java.util.prefs.Preferences
- **java.sql**（16）：java.sql.Connection、java.sql.DatabaseMetaData、java.sql.Date、java.sql.Driver、java.sql.DriverManager、java.sql.DriverPropertyInfo、java.sql.PreparedStatement、java.sql.ResultSet、java.sql.ResultSetMetaData、java.sql.SQLException、java.sql.SQLFeatureNotSupportedException、java.sql.Statement、java.sql.Time、java.sql.Timestamp、java.sql.Types、javax.sql.DataSource
- **java.xml**（32）：javax.xml.XMLConstants、javax.xml.namespace.QName、javax.xml.parsers.DocumentBuilder、javax.xml.parsers.DocumentBuilderFactory、javax.xml.parsers.ParserConfigurationException、javax.xml.stream.XMLOutputFactory、javax.xml.stream.XMLStreamException、javax.xml.stream.XMLStreamWriter、javax.xml.transform.Result、javax.xml.transform.Source、javax.xml.transform.Templates、javax.xml.transform.Transformer、javax.xml.transform.TransformerException、javax.xml.transform.TransformerFactory、javax.xml.transform.stream.StreamResult、javax.xml.transform.stream.StreamSource、javax.xml.validation.Schema、javax.xml.validation.SchemaFactory、javax.xml.validation.Validator、javax.xml.xpath.XPath、javax.xml.xpath.XPathConstants、javax.xml.xpath.XPathExpressionException、javax.xml.xpath.XPathFactory、org.w3c.dom.Document、org.w3c.dom.Element、org.w3c.dom.Node、org.w3c.dom.NodeList、org.xml.sax.ErrorHandler、org.xml.sax.InputSource、org.xml.sax.SAXException、org.xml.sax.SAXParseException、org.xml.sax.helpers.DefaultHandler

> 逐类型证据（引用计数与引用类名）见同目录 `api-usage.csv`。
