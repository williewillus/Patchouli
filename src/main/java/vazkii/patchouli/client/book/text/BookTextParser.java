package vazkii.patchouli.client.book.text;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.gui.GuiBook;
import vazkii.patchouli.client.book.gui.GuiBookEntry;
import vazkii.patchouli.common.base.Patchouli;
import vazkii.patchouli.common.book.Book;

import javax.annotation.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

public class BookTextParser {
	public static final LiteralText EMPTY_STRING_COMPONENT = new LiteralText("");
	// A command lookup takes the body of a command $(...) and the current span state.
	// If it understands the command, it can modify the span state and return Optional.of(command replacement).
	// Otherwise, it just returns Optional.empty().
	// TODO: Make this part of the API, perhaps?
	private static final List<CommandLookup> COMMAND_LOOKUPS = new ArrayList<>();
	private static final Map<String, CommandProcessor> COMMANDS = new ConcurrentHashMap<>();
	private static final Map<String, FunctionProcessor> FUNCTIONS = new ConcurrentHashMap<>();

	private static void registerProcessor(CommandLookup processor) {
		COMMAND_LOOKUPS.add(processor);
	}

	public static void register(CommandProcessor handler, String... names) {
		for (String name : names) {
			COMMANDS.put(name, handler);
		}
	}

	public static void register(FunctionProcessor function, String... names) {
		for (String name : names) {
			FUNCTIONS.put(name, function);
		}
	}

	static {
		registerProcessor(BookTextParser::colorCodeProcessor);
		registerProcessor(BookTextParser::colorHexProcessor);
		registerProcessor(BookTextParser::listProcessor);
		registerProcessor(BookTextParser::lookupFunctionProcessor);
		registerProcessor(BookTextParser::lookupCommandProcessor);
		register(state -> {
			state.lineBreaks = 1;
			return "";
		}, "br");
		register(state -> {
			state.lineBreaks = 2;
			return "";
		}, "br2", "2br", "p");
		register(state -> {
			state.endingExternal = state.isExternalLink;
			state.popStyle();
			state.cluster = null;
			state.tooltip = EMPTY_STRING_COMPONENT;
			state.onClick = null;
			state.isExternalLink = false;
			return "";
		}, "/l");
		register(state -> {
			state.cluster = null;
			state.tooltip = EMPTY_STRING_COMPONENT;
			return "";
		}, "/t");
		register(state -> state.gui.getMinecraft().player.getName().getString(), "playername"); // TODO 1.16: dropped format codes
		register(state -> {
			state.modifyStyle(s -> s.withFormatting(Formatting.OBFUSCATED));
			return "";
		}, "k", "obf");
		register(state -> {
			state.modifyStyle(s -> s.withFormatting(Formatting.BOLD));
			return "";
		}, "l", "bold");
		register(state -> {
			state.modifyStyle(s -> s.withFormatting(Formatting.STRIKETHROUGH));
			return "";
		}, "m", "strike");
		register(state -> {
			state.modifyStyle(s -> s.withFormatting(Formatting.ITALIC));
			return "";
		}, "o", "italic", "italics");
		register(state -> {
			state.reset();
			return "";
		}, "", "reset", "clear");
		register(state -> {
			state.baseColor();
			return "";
		}, "nocolor");

		register((parameter, state) -> {
			KeyBinding result = getKeybindKey(state, parameter);
			if (result == null) {
				state.tooltip = new TranslatableText("patchouli.gui.lexicon.keybind_missing", parameter);
				return "N/A";
			}

			state.tooltip = new TranslatableText("patchouli.gui.lexicon.keybind", new TranslatableText(result.getTranslationKey()));
			return result.getBoundKeyLocalizedText().getString();
		}, "k");
		register((parameter, state) -> {
			state.cluster = new LinkedList<>();

			state.pushStyle(Style.EMPTY.withColor(TextColor.fromRgb(state.book.linkColor)));
			boolean isExternal = parameter.matches("^https?\\:.*");

			if (isExternal) {
				String url = parameter;
				state.tooltip = new TranslatableText("patchouli.gui.lexicon.external_link");
				state.isExternalLink = true;
				state.onClick = () -> {
					GuiBook.openWebLink(url);
					return true;
				};
			} else {
				int hash = parameter.indexOf('#');
				String anchor = null;
				if (hash >= 0) {
					anchor = parameter.substring(hash + 1);
					parameter = parameter.substring(0, hash);
				}

				Identifier href = parameter.contains(":") ? new Identifier(parameter) : new Identifier(state.book.getModNamespace(), parameter);
				BookEntry entry = state.book.contents.entries.get(href);
				if (entry != null) {
					state.tooltip = entry.isLocked()
							? new TranslatableText("patchouli.gui.lexicon.locked").formatted(Formatting.GRAY)
							: entry.getName();
					GuiBook gui = state.gui;
					Book book = state.book;
					int page = 0;
					if (anchor != null) {
						int anchorPage = entry.getPageFromAnchor(anchor);
						if (anchorPage >= 0) {
							page = anchorPage / 2;
						} else {
							state.tooltip.append(" (INVALID ANCHOR:" + anchor + ")");
						}
					}
					int finalPage = page;
					state.onClick = () -> {
						GuiBookEntry entryGui = new GuiBookEntry(book, entry, finalPage);
						gui.displayLexiconGui(entryGui, true);
						GuiBook.playBookFlipSound(book);
						return true;
					};
				} else {
					state.tooltip = new LiteralText("BAD LINK: " + parameter);
				}
			}
			return "";
		}, "l");
		register((parameter, state) -> {
			state.tooltip = new LiteralText(parameter);
			state.cluster = new LinkedList<>();
			return "";
		}, "tooltip", "t");
		register((parameter, state) -> {
			state.pushStyle(Style.EMPTY.withColor(TextColor.fromRgb(state.book.linkColor)));
			state.cluster = new LinkedList<>();
			if (!parameter.startsWith("/")) {
				state.tooltip = new LiteralText("INVALID COMMAND (must begin with /)");
			} else {
				state.tooltip = new LiteralText(parameter.length() < 20 ? parameter : parameter.substring(0, 20) + "...");
			}
			state.onClick = () -> {
				state.gui.getMinecraft().player.sendChatMessage(parameter);
				return true;
			};
			return "";
		}, "command", "c");
		register(state -> {
			state.popStyle();
			state.cluster = null;
			state.tooltip = EMPTY_STRING_COMPONENT;
			state.onClick = null;
			return "";
		}, "/c");
	}

	private final GuiBook gui;
	private final Book book;
	private final int x, y, width;
	private final int lineHeight;
	private final Style baseStyle;

	public BookTextParser(GuiBook gui, Book book, int x, int y, int width, int lineHeight, Style baseStyle) {
		this.gui = gui;
		this.book = book;
		this.x = x;
		this.y = y;
		this.width = width;
		this.lineHeight = lineHeight;
		this.baseStyle = baseStyle;
	}

	public List<Span> parse(Text text) {
		List<Span> spans = new ArrayList<>();
		SpanState state = new SpanState(gui, book, baseStyle);
		text.visitSelf((style, string) -> {
			spans.addAll(processCommands(expandMacros(string), state, style));
			return Optional.empty();
		}, baseStyle);
		return spans;
	}

	public String expandMacros(@Nullable String text) {
		String actualText = text;
		if (actualText == null) {
			actualText = "[ERROR]";
		}

		int i = 0;
		int expansionCap = 10;
		for (; i < expansionCap; i++) {
			String newText = actualText;
			for (Map.Entry<String, String> e : book.macros.entrySet()) {
				newText = newText.replace(e.getKey(), e.getValue());
			}

			if (newText.equals(actualText)) {
				break;
			}
			actualText = newText;
		}

		if (i == expansionCap) {
			Patchouli.LOGGER.warn("Expanded macros for {} iterations without reaching fixpoint, stopping. " +
					"Make sure you don't have circular macro invocations", expansionCap);
		}

		return actualText;
	}

	private Pattern COMMAND_PATTERN = Pattern.compile("\\$\\(([^)]*)\\)");

	/**
	 * Takes in the raw book source and computes a collection of spans from it.
	 */
	private List<Span> processCommands(String text, SpanState state, Style style) {
		state.changeBaseStyle(style);
		List<Span> spans = new ArrayList<>();
		Matcher match = COMMAND_PATTERN.matcher(text);

		while (match.find()) {
			StringBuilder sb = new StringBuilder();
			// Extract the portion before the match to sb
			match.appendReplacement(sb, "");
			spans.add(new Span(state, sb.toString()));

			try {
				String processed = processCommand(state, match.group(1));
				if (!processed.isEmpty()) {
					spans.add(new Span(state, processed));

					if (state.cluster == null) {
						state.tooltip = EMPTY_STRING_COMPONENT;
					}
				}
			} catch (Exception ex) {
				spans.add(Span.error(state, "[ERROR]"));
			}
		}
		// Extract the portion after all matches
		spans.add(new Span(state, match.appendTail(new StringBuffer()).toString()));
		return spans;
	}

	private String processCommand(SpanState state, String cmd) {
		state.endingExternal = false;

		Optional<String> optResult = Optional.empty();
		for (CommandLookup lookup : COMMAND_LOOKUPS) {
			optResult = lookup.process(cmd, state);
			if (optResult.isPresent()) {
				break;
			}
		}
		String result = optResult.orElse("$(" + cmd + ")");

		if (state.endingExternal) {
			result += Formatting.GRAY + "\u21AA";
		}

		return result;
	}

	private static Optional<String> colorCodeProcessor(String functionName, SpanState state) {
		if (functionName.length() == 1 && functionName.matches("^[0123456789abcdef]$")) {
			state.modifyStyle(s -> s.withFormatting(Formatting.byCode(functionName.charAt(0))));
			return Optional.of("");
		}
		return Optional.empty();
	}

	private static Optional<String> colorHexProcessor(String functionName, SpanState state) {
		if (functionName.startsWith("#") && (functionName.length() == 4 || functionName.length() == 7)) {
			TextColor color;
			String parse = functionName.substring(1);
			if (parse.length() == 3) {
				parse = "" + parse.charAt(0) + parse.charAt(0) + parse.charAt(1) + parse.charAt(1) + parse.charAt(2) + parse.charAt(2);
			}
			try {
				color = TextColor.fromRgb(Integer.parseInt(parse, 16));
			} catch (NumberFormatException e) {
				color = state.getBase().getColor();
			}
			state.color(color);
			return Optional.of("");
		}
		return Optional.empty();
	}

	private static Optional<String> listProcessor(String functionName, SpanState state) {
		if (functionName.matches("li\\d?")) {
			char c = functionName.length() > 2 ? functionName.charAt(2) : '1';
			int dist = Character.isDigit(c) ? Character.digit(c, 10) : 1;
			int pad = dist * 4;
			char bullet = dist % 2 == 0 ? '\u25E6' : '\u2022';
			state.lineBreaks = 1;
			state.spacingLeft = pad;
			state.spacingRight = state.spaceWidth;
			return Optional.of(Formatting.BLACK.toString() + bullet);
		}
		return Optional.empty();
	}

	private static Optional<String> lookupFunctionProcessor(String functionName, SpanState state) {
		int index = functionName.indexOf(':');
		if (index > 0) {
			String fname = functionName.substring(0, index), param = functionName.substring(index + 1);
			return Optional.of(
					Optional.ofNullable(FUNCTIONS.get(fname))
							.map(f -> f.process(param, state))
							.orElse("[MISSING FUNCTION: " + fname + "]"));
		}
		return Optional.empty();
	}

	private static Optional<String> lookupCommandProcessor(String functionName, SpanState state) {
		return Optional.ofNullable(COMMANDS.get(functionName)).map(c -> c.process(state));
	}

	private static KeyBinding getKeybindKey(SpanState state, String keybind) {
		String alt = "key." + keybind;

		KeyBinding[] keys = state.gui.getMinecraft().options.keysAll;
		for (var k : keys) {
			String name = k.getTranslationKey();
			if (name.equals(keybind) || name.equals(alt)) {
				return k;
			}
		}

		return null;
	}

	public interface CommandLookup {
		Optional<String> process(String commandName, SpanState state);
	}

	public interface CommandProcessor {
		String process(SpanState state);
	}

	public interface FunctionProcessor {
		String process(String parameter, SpanState state);
	}

}
