/*
 * Copyright (c) 2026, AJD
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.customnpcmodels.ui;

import com.customnpcmodels.packs.PackView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.SwingUtil;

/**
 * The side panel: every pack the plugin found, each switched on or off as a whole or model by model,
 * in the order they take priority.
 *
 * <p>It only ever shows the last {@link PackView} snapshot it was handed and reports what the user
 * does through {@link Actions}, which writes config. The plugin reacts to that write and hands a new
 * snapshot back, so the panel holds no state of its own beyond what is expanded and searched. EDT
 * only, like all Swing.
 */
public class PacksPanel extends PluginPanel
{
	/** Content width inside the panel's borders, for wrapping text. */
	private static final int TEXT_WIDTH = PANEL_WIDTH - 40;

	/** How wide Swing's HTML renderer draws one CSS pixel, in screen pixels. */
	private static final float CSS_PIXEL = 1.3f;

	/** What the panel asks of the plugin. All called on the EDT. */
	public interface Actions
	{
		void setPackEnabled(String packId, boolean enabled);

		void setModelEnabled(String modelKey, boolean enabled);

		/** Every pack id, highest priority first. */
		void setOrder(List<String> packIds);

		/** Asks the user for a pack folder and imports it. */
		void importPack();

		/** Reads every pack from disk again. */
		void refresh();
	}

	private final Actions actions;
	private final IconTextField search = new IconTextField();
	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 6));

	/** Packs whose model list is open. Kept across snapshots, so a toggle does not fold it shut. */
	private final Set<String> expanded = new HashSet<>();

	private List<PackView> packs = Collections.emptyList();

	public PacksPanel(Actions actions)
	{
		super(false);
		this.actions = actions;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		header.setBorder(new EmptyBorder(10, 10, 6, 10));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Custom NPC Models");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title);

		JLabel help = new JLabel(wrap("Switch packs and models on or off. Where two packs have a model "
			+ "for the same NPC, the higher one is drawn."));
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(help);

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		header.add(search);

		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton importButton = new JButton("Import pack...");
		importButton.setToolTipText("Copy a pack folder - one holding a bundle.dat - into your local packs");
		importButton.addActionListener(e -> actions.importPack());
		JButton refreshButton = new JButton("Refresh");
		refreshButton.setToolTipText("Read every pack from disk again");
		refreshButton.addActionListener(e -> actions.refresh());
		buttons.add(importButton);
		buttons.add(refreshButton);
		header.add(buttons);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(status);

		add(header, BorderLayout.NORTH);

		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setBorder(new EmptyBorder(0, 10, 10, 10));
		// Held to the panel's width: a plain panel in a viewport is as wide as it would like to be,
		// and with no horizontal scrolling, anything past the edge - the priority buttons - is cut off
		JPanel top = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(PANEL_WIDTH, super.getPreferredSize().height);
			}
		};
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);

		rebuild();
	}

	/** Shows a new snapshot. EDT only. */
	public void setPacks(List<PackView> packs)
	{
		this.packs = packs;
		rebuild();
	}

	/**
	 * Says how the last import went, or anything else the user should know - in red when something
	 * went wrong, so it is not missed. Null clears it. EDT only.
	 */
	public void showStatus(String message, boolean error)
	{
		status.setText(message == null ? "" : wrap(message));
		status.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
	}

	private void rebuild()
	{
		SwingUtil.fastRemoveAll(list);

		List<String> terms = terms(search.getText());
		List<PackView> shown = packs.stream()
			.filter(pack -> terms.isEmpty() || matches(pack, terms))
			.collect(Collectors.toList());

		if (shown.isEmpty())
		{
			PluginErrorPanel empty = new PluginErrorPanel();
			empty.setContent(packs.isEmpty() ? "No packs" : "No matches",
				packs.isEmpty() ? "Import a pack to get started." : "");
			list.add(empty);
		}
		else
		{
			for (PackView pack : shown)
			{
				list.add(packItem(pack));
			}
		}

		list.revalidate();
		list.repaint();
	}

	private JPanel packItem(PackView pack)
	{
		JPanel item = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
		item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		item.setBorder(new EmptyBorder(6, 6, 6, 6));

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setOpaque(false);
		// A pack that can't be read draws nothing, whatever its switch says, so it shows as off - a
		// ticked box would suggest its models are in use
		boolean readable = pack.getError() == null;
		JCheckBox enabled = new JCheckBox();
		enabled.setOpaque(false);
		enabled.setSelected(pack.isEnabled() && readable);
		enabled.setEnabled(readable);
		enabled.setToolTipText(!readable ? "This pack can't be read, so nothing in it is drawn"
			: pack.isEnabled() ? "Switch this pack off" : "Switch this pack on");
		// An action listener only hears the user; the setSelected above does not fire it
		enabled.addActionListener(e -> actions.setPackEnabled(pack.getId(), enabled.isSelected()));
		top.add(enabled, BorderLayout.WEST);

		JLabel name = new JLabel(wrap(pack.getName(), TEXT_WIDTH - 60));
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(pack.isEnabled() && readable ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		top.add(name, BorderLayout.CENTER);

		int index = packs.indexOf(pack);
		JPanel order = new JPanel(new java.awt.GridLayout(1, 2, 2, 0));
		order.setOpaque(false);
		order.add(orderButton("▲", "Take priority over the pack above", index > 0, () -> movePack(index, -1)));
		order.add(orderButton("▼", "Give priority to the pack below", index < packs.size() - 1,
			() -> movePack(index, 1)));
		top.add(order, BorderLayout.EAST);
		item.add(top);

		item.add(detail(describe(pack), ColorScheme.LIGHT_GRAY_COLOR));
		if (pack.getDescription() != null && !pack.getDescription().trim().isEmpty())
		{
			item.add(detail(pack.getDescription(), ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (!readable)
		{
			item.add(detail("Could not be read: " + pack.getError(), ColorScheme.PROGRESS_ERROR_COLOR));
			return item;
		}

		boolean open = expanded.contains(pack.getId());
		JLabel toggle = new JLabel((open ? "▾ " : "▸ ") + pack.getModels().size() + " model"
			+ (pack.getModels().size() == 1 ? "" : "s"));
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!expanded.remove(pack.getId()))
				{
					expanded.add(pack.getId());
				}
				rebuild();
			}
		});
		item.add(toggle);

		if (open)
		{
			for (PackView.ModelView model : pack.getModels())
			{
				item.add(modelRow(model));
			}
		}
		return item;
	}

	private JPanel modelRow(PackView.ModelView model)
	{
		JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(0, 12, 2, 0));

		String ids = model.getNpcIds().stream().map(String::valueOf).collect(Collectors.joining(", "));
		JCheckBox enabled = new JCheckBox(wrap(model.getName() + " (NPC " + ids + ")", TEXT_WIDTH - 40));
		enabled.setOpaque(false);
		enabled.setFont(FontManager.getRunescapeSmallFont());
		enabled.setSelected(model.isEnabled() && !model.isNeverDrawn());
		enabled.setEnabled(!model.isNeverDrawn());
		enabled.addActionListener(e -> actions.setModelEnabled(model.getKey(), enabled.isSelected()));
		row.add(enabled);

		if (!model.getBlocked().isEmpty())
		{
			// Fixed in code, with no setting: said plainly, so it is not mistaken for a fault
			row.add(detail((model.isNeverDrawn() ? "Never swapped" : "Some NPCs never swapped") + ": NPCs in "
				+ String.join(", ", model.getBlocked()) + " can't have custom models.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		if (model.getOverriddenBy() != null)
		{
			row.add(detail("Overridden by " + model.getOverriddenBy() + ", which is higher.",
				ColorScheme.PROGRESS_INPROGRESS_COLOR));
		}
		return row;
	}

	private void movePack(int index, int delta)
	{
		List<String> ids = packs.stream().map(PackView::getId).collect(Collectors.toList());
		Collections.swap(ids, index, index + delta);
		actions.setOrder(ids);
	}

	private static JButton orderButton(String text, String tooltip, boolean enabled, Runnable onClick)
	{
		JButton button = new JButton(text);
		button.setToolTipText(tooltip);
		button.setEnabled(enabled);
		button.setFont(button.getFont().deriveFont(Font.PLAIN, 10f));
		button.setMargin(new java.awt.Insets(0, 2, 0, 2));
		SwingUtil.removeButtonDecorations(button);
		button.addActionListener(e -> onClick.run());
		return button;
	}

	private static JLabel detail(String text, Color color)
	{
		JLabel label = new JLabel(wrap(text, TEXT_WIDTH));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	private static String describe(PackView pack)
	{
		StringBuilder text = new StringBuilder(pack.getKind().getLabel());
		if (pack.getAuthor() != null && !pack.getAuthor().trim().isEmpty())
		{
			text.append(" · by ").append(pack.getAuthor().trim());
		}
		if (pack.getVersion() != null && !pack.getVersion().trim().isEmpty())
		{
			text.append(" · v").append(pack.getVersion().trim());
		}
		return text.toString();
	}

	private static List<String> terms(String query)
	{
		List<String> terms = new ArrayList<>();
		for (String term : query.toLowerCase(Locale.ROOT).split("\\s+"))
		{
			if (!term.isEmpty())
			{
				terms.add(term);
			}
		}
		return terms;
	}

	/** Every term found in the pack's name, author or kind, or in one of its models' names or ids. */
	private static boolean matches(PackView pack, List<String> terms)
	{
		StringBuilder haystack = new StringBuilder()
			.append(pack.getName()).append(' ')
			.append(pack.getKind().getLabel());
		if (pack.getAuthor() != null)
		{
			haystack.append(' ').append(pack.getAuthor());
		}
		for (PackView.ModelView model : pack.getModels())
		{
			haystack.append(' ').append(model.getName()).append(' ').append(model.getNpcIds());
		}
		String text = haystack.toString().toLowerCase(Locale.ROOT);
		return terms.stream().allMatch(text::contains);
	}

	private static String wrap(String text)
	{
		return wrap(text, TEXT_WIDTH);
	}

	/** Text as HTML, so a label wraps at {@code width} rather than running off the panel. */
	private static String wrap(String text, int width)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		// Swing's HTML renderer draws a CSS pixel 1.3 screen pixels wide, so ask for less
		return "<html><body style='width:" + Math.round(width / CSS_PIXEL) + "px'>" + escaped + "</body></html>";
	}
}
