import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает схемы формата EDT из плагинов установленной 1C:EDT.
 *
 * Метамодель описана в Xcore: файлы `model/*.xcore` лежат внутри jar плагинов.
 * Рядом в plugin.xml каждый пакет EMF объявлен тройкой «nsURI, класс, xcore»,
 * поэтому машиночитаемая форма берётся из самого пакета: класс загружается,
 * у него читается eINSTANCE и сохраняется как .ecore.
 *
 * Состав плагинов берётся из bundles.info конкретной установки: в общем пуле p2
 * лежат сразу несколько версий EDT, и смешивать их нельзя.
 *
 * Работа с EMF идёт через рефлексию намеренно: пакеты и сам EMF должны
 * оказаться в одном загрузчике классов, иначе типы не совпадут.
 *
 * Запуск: java ExtractEdtSchemas.java <bundles.info> <каталог результата>
 */
public final class ExtractEdtSchemas {

	/**
	 * Строка bundles.info: имя, версия, ссылка на jar, уровень, запуск.
	 *
	 * Ссылка бывает абсолютной (`file:/opt/...`, на Windows `file:/C:/...`),
	 * относительной каталогу установки и вовсе без схемы (`plugins/...`),
	 * иногда с префиксом `reference:`.
	 */
	private static final Pattern BUNDLE_LINE = Pattern.compile("^([^,#]+),([^,]+),(?:reference:)?(?:file:)?([^,]+),");

	/** Имя файла плагина: имя, подчёркивание, версия. */
	private static final Pattern BUNDLE_FILE = Pattern.compile("^(.+)_(\\d+\\.\\d+.*)$");

	/** Объявление пакета EMF в plugin.xml. */
	private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
		"<package\\s+[^>]*?uri=\"([^\"]+)\"[^>]*?class=\"([^\"]+)\"[^>]*?genModel=\"([^\"]+)\"",
		Pattern.DOTALL);

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Использование: ExtractEdtSchemas <bundles.info> <каталог результата>");
			System.exit(2);
		}

		Path bundlesInfo = Path.of(args[0]);
		Path outDir = Path.of(args[1]);
		Path xcoreDir = outDir.resolve("xcore");
		Path ecoreDir = outDir.resolve("ecore");
		Files.createDirectories(xcoreDir);
		Files.createDirectories(ecoreDir);

		List<Path> jars = readBundles(bundlesInfo);
		System.out.println("Плагинов в установке: " + jars.size());
		if (jars.isEmpty()) {
			System.err.println("Плагины не прочитаны: проверьте пути в " + bundlesInfo);
			System.exit(1);
		}

		URL[] urls = new URL[jars.size()];
		for (int i = 0; i < jars.size(); i++) {
			urls[i] = jars.get(i).toUri().toURL();
		}

		int models = 0;
		int packages = 0;
		try (URLClassLoader loader = new URLClassLoader(urls, ExtractEdtSchemas.class.getClassLoader())) {
			for (Path jar : jars) {
				try (JarFile file = new JarFile(jar.toFile())) {
					List<String> xcoreEntries = xcoreEntries(file);
					if (xcoreEntries.isEmpty()) {
						continue;
					}

					String bundle = bundleName(jar);
					for (String entry : xcoreEntries) {
						Path target = xcoreDir.resolve(bundle).resolve(fileName(entry));
						Files.createDirectories(target.getParent());
						try (InputStream in = file.getInputStream(file.getEntry(entry))) {
							Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						}
						models++;
					}

					for (Map.Entry<String, String> declared : declaredPackages(file).entrySet()) {
						if (savePackage(loader, declared.getValue(), declared.getKey(), ecoreDir)) {
							packages++;
						}
					}
				}
			}
		}

		System.out.println("Схем Xcore: " + models);
		System.out.println("Пакетов Ecore: " + packages);
		if (models == 0 || packages == 0) {
			System.err.println("Схемы не извлечены: в плагинах не нашлось ни моделей, ни пакетов");
			System.exit(1);
		}
	}

	/**
	 * Пути к jar из bundles.info.
	 *
	 * Относительные ссылки считаются от каталога установки: bundles.info лежит
	 * в `<установка>/configuration/org.eclipse.equinox.simpleconfigurator`.
	 */
	private static List<Path> readBundles(Path bundlesInfo) throws IOException {
		Path installation = bundlesInfo.toAbsolutePath().getParent().getParent().getParent();
		List<Path> jars = new ArrayList<>();
		int missing = 0;
		String firstReference = "";

		for (String line : Files.readAllLines(bundlesInfo)) {
			Matcher matcher = BUNDLE_LINE.matcher(line.trim());
			if (!matcher.find()) {
				continue;
			}

			String reference = matcher.group(3).replaceFirst("^/([A-Za-z]:)", "$1");
			if (firstReference.isEmpty()) {
				firstReference = reference;
			}
			Path jar = Path.of(reference);
			if (!jar.isAbsolute()) {
				jar = installation.resolve(reference);
			}
			if (Files.isRegularFile(jar)) {
				jars.add(jar.normalize());
			} else {
				missing++;
			}
		}

		if (missing > 0) {
			System.out.println("Плагинов не найдено на диске: " + missing);
			if (jars.isEmpty() && !firstReference.isEmpty()) {
				System.out.println("Пример ссылки: " + firstReference);
				System.out.println("Каталог установки: " + installation);
			}
		}
		return jars;
	}

	/** Записи `model/*.xcore` в плагине. */
	private static List<String> xcoreEntries(JarFile file) {
		List<String> found = new ArrayList<>();
		for (JarEntry entry : java.util.Collections.list(file.entries())) {
			if (entry.getName().startsWith("model/") && entry.getName().endsWith(".xcore")) {
				found.add(entry.getName());
			}
		}
		return found;
	}

	/** Пакеты EMF из plugin.xml: nsURI - класс пакета. */
	private static Map<String, String> declaredPackages(JarFile file) throws IOException {
		JarEntry pluginXml = file.getJarEntry("plugin.xml");
		if (pluginXml == null) {
			return Map.of();
		}

		String content;
		try (InputStream in = file.getInputStream(pluginXml)) {
			content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}

		Map<String, String> packages = new LinkedHashMap<>();
		Matcher matcher = PACKAGE_DECLARATION.matcher(content);
		while (matcher.find()) {
			packages.put(matcher.group(1), matcher.group(2));
		}
		return packages;
	}

	/**
	 * Сохраняет пакет EMF как .ecore.
	 *
	 * @return true, если файл записан
	 */
	private static boolean savePackage(ClassLoader loader, String className, String nsUri, Path ecoreDir) {
		try {
			Class<?> packageClass = Class.forName(className, true, loader);
			Object ePackage = packageClass.getField("eINSTANCE").get(null);

			Class<?> uriClass = Class.forName("org.eclipse.emf.common.util.URI", true, loader);
			Class<?> resourceClass = Class.forName("org.eclipse.emf.ecore.resource.Resource", true, loader);
			// Ресурс создаётся напрямую: вне OSGi фабрики расширений не зарегистрированы
			Class<?> resourceImplClass = Class.forName("org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl", true, loader);

			Path target = ecoreDir.resolve(fileNameForNs(nsUri) + ".ecore");
			Files.createDirectories(target.getParent());

			Method createFileUri = uriClass.getMethod("createFileURI", String.class);
			Object uri = createFileUri.invoke(null, target.toAbsolutePath().toString());
			Object resource = resourceImplClass.getConstructor(uriClass).newInstance(uri);

			Method getContents = resourceClass.getMethod("getContents");
			@SuppressWarnings("unchecked")
			List<Object> contents = (List<Object>) getContents.invoke(resource);
			contents.add(ePackage);

			Method save = resourceClass.getMethod("save", Map.class);
			save.invoke(resource, Map.of());
			return true;
		} catch (ReflectiveOperationException | RuntimeException | IOException error) {
			System.err.println("Пакет " + className + " пропущен: " + rootCause(error));
			return false;
		}
	}

	/** Имя файла из nsURI: `http://g5.1c.ru/v8/dt/metadata/mdclass` - `g5.1c.ru.v8.dt.metadata.mdclass`. */
	private static String fileNameForNs(String nsUri) {
		return nsUri.replaceFirst("^\\w+://", "").replace('/', '.').replaceAll("[^\\w.-]", "_");
	}

	/** Имя плагина без версии: подчёркивание встречается и в самом имени (`com._1c...`). */
	private static String bundleName(Path jar) {
		String name = jar.getFileName().toString().replaceFirst("\\.jar$", "");
		Matcher matcher = BUNDLE_FILE.matcher(name);
		return matcher.matches() ? matcher.group(1) : name;
	}

	private static String fileName(String entry) {
		return entry.substring(entry.lastIndexOf('/') + 1);
	}

	private static String rootCause(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}
}
