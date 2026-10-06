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

import com.customnpcmodels.packs.HubEntry;
import com.customnpcmodels.packs.PackKind;
import com.customnpcmodels.packs.PackView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;

/**
 * The side panel: every pack the plugin found, each switched on or off as a whole or model by model,
 * in the order they take priority.
 *
 * <p>It only ever shows the last {@link PackView} snapshot it was handed and reports what the user
 * does through {@link Actions}, which writes config. The plugin reacts to that write and hands a new
 * snapshot back, so the panel keeps no pack state of its own. What it does keep is presentation:
 * what is expanded and searched, the hub's last answer and icons, and which buttons are busy. EDT
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

		/**
		 * Fetches the hub's list of packs, answering with {@link #setHubEntries} or {@link #setHubError}.
		 * Only asked while the hub is switched on. If it is switched off before the answer arrives, no
		 * answer comes; {@link #setHubEnabled}{@code (false)} clears the wait instead.
		 */
		void loadHub();

		/** Downloads and installs a hub pack, or updates it when it is installed. */
		void installHubPack(HubEntry entry);

		/** Removes an installed hub pack, by its folder under {@code hub/}. */
		void removeHubPack(String folder);

		/** Deletes a local pack, by its folder under {@code local/}. */
		void removeLocalPack(String folder);
	}

	/** Icons are shown this size, whatever size the pack ships. */
	private static final int ICON_SIZE = 32;

	private final Actions actions;
	private final IconTextField search = new IconTextField();
	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 6));

	/** Packs whose model list is open. Kept across snapshots, so a toggle does not fold it shut. */
	private final Set<String> expanded = new HashSet<>();

	private List<PackView> packs = Collections.emptyList();

	/** Whether the hub is switched on in config. While it is off, the panel asks nothing of it. */
	private boolean hubEnabled;

	/** The hub's packs as last fetched; null until they are. */
	private List<HubEntry> hubEntries;
	private boolean hubLoading;
	private String hubError;

	/** Hub pack icons, by the commit they were fetched at. */
	private final Map<String, ImageIcon> hubIcons = new HashMap<>();

	/**
	 * Packs with an install or removal under way, so their button can't be pressed twice: hub packs by
	 * their hub id, local packs by their pack id.
	 */
	private final Set<String> busy = new HashSet<>();

	/**
	 * The cards the list shows, by key, with what each was built to show. A rebuild keeps every card
	 * whose content is unchanged, so switching one pack doesn't clear and rebuild the whole list.
	 */
	private Map<String, Card> cards = new HashMap<>();

	/** A card in the list, and everything it was built from. */
	private static final class Card
	{
		private final List<Object> shows;
		private final JComponent component;

		private Card(List<Object> shows, JComponent component)
		{
			this.shows = shows;
			this.component = component;
		}
	}

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

		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton importButton = new JButton("Import pack...");
		importButton.setToolTipText("Copy a pack folder - one holding a bundle.dat - into your local packs");
		importButton.addActionListener(e -> actions.importPack());
		JButton refreshButton = new JButton("Refresh");
		refreshButton.setToolTipText("Read every pack from disk again, and the hub's list when it is on");
		refreshButton.addActionListener(e ->
		{
			actions.refresh();
			if (hubEnabled)
			{
				requestHub();
			}
		});
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
	 * Whether the hub is switched on. Switching it on fetches its packs straight away if the panel is
	 * open, and otherwise the next time it is. EDT only.
	 */
	public void setHubEnabled(boolean enabled)
	{
		hubEnabled = enabled;
		if (!enabled)
		{
			hubEntries = null;
			hubError = null;
			hubLoading = false;
		}
		else if (isShowing() && hubEntries == null && !hubLoading)
		{
			requestHub();
			return;
		}
		rebuild();
	}

	/** The hub's packs, as just fetched. EDT only. */
	public void setHubEntries(List<HubEntry> entries)
	{
		if (!hubEnabled)
		{
			// An answer to a fetch made before the hub was switched off
			return;
		}
		hubEntries = entries;
		hubLoading = false;
		hubError = null;
		rebuild();
	}

	/** Why the hub's packs could not be fetched. EDT only. */
	public void setHubError(String error)
	{
		if (!hubEnabled)
		{
			return;
		}
		hubLoading = false;
		hubError = error;
		rebuild();
	}

	/** A hub pack's icon, fetched after its entry. EDT only. */
	public void setHubIcon(String commit, BufferedImage icon)
	{
		hubIcons.put(commit, new ImageIcon(ImageUtil.resizeImage(icon, ICON_SIZE, ICON_SIZE, true)));
		rebuild();
	}

	/**
	 * Marks an install or removal as under way, or done. {@code key} is a hub pack's id, or a local
	 * pack's whole pack id. EDT only.
	 */
	public void setBusy(String key, boolean isBusy)
	{
		if (isBusy ? busy.add(key) : busy.remove(key))
		{
			rebuild();
		}
	}

	@Override
	public void onActivate()
	{
		// The hub's packs are fetched when the panel is first opened, not when the plugin starts
		if (hubEnabled && hubEntries == null && !hubLoading)
		{
			requestHub();
		}
	}

	private void requestHub()
	{
		if (hubLoading)
		{
			// One fetch at a time: its answer covers whoever asked second
			return;
		}
		hubLoading = true;
		hubError = null;
		rebuild();
		actions.loadHub();
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
		Map<String, Card> kept = new HashMap<>();
		List<Component> wanted = new ArrayList<>();

		List<String> terms = terms(search.getText());
		List<PackView> shown = packs.stream()
			.filter(pack -> terms.isEmpty() || matches(pack, terms))
			.collect(Collectors.toList());

		if (shown.isEmpty())
		{
			String title = packs.isEmpty() ? "No packs" : "No matches";
			String text = packs.isEmpty() ? "Import a pack to get started." : "";
			wanted.add(card(kept, "empty", Arrays.asList(title, text), () ->
			{
				PluginErrorPanel empty = new PluginErrorPanel();
				empty.setContent(title, text);
				return empty;
			}));
		}
		else
		{
			for (PackView pack : shown)
			{
				// Everything packItem reads, so a card is only built again when what it shows changed
				int index = packs.indexOf(pack);
				List<Object> shows = Arrays.asList(pack, index == 0, index == packs.size() - 1,
					expanded.contains(pack.getId()), busy.contains(pack.getId()), busy.contains(hubFolder(pack)),
					pack.getCommit() == null ? null : hubIcons.get(pack.getCommit()));
				wanted.add(card(kept, "pack:" + pack.getId(), shows, () -> packItem(pack)));
			}
		}

		addHubSection(terms, kept, wanted);

		cards = kept;
		show(wanted);
	}

	/**
	 * The card under {@code key}: the one already built when it was built to show the same
	 * {@code shows}, else a new one. Either way it is kept in {@code kept} for the next rebuild.
	 */
	private JComponent card(Map<String, Card> kept, String key, List<Object> shows, Supplier<JComponent> build)
	{
		Card card = cards.get(key);
		if (card == null || !card.shows.equals(shows))
		{
			card = new Card(shows, build.get());
		}
		kept.put(key, card);
		return card.component;
	}

	/**
	 * Puts {@code wanted} in the list, in order, moving only what has to move. A card already in its
	 * place is left alone, rather than the list being emptied and filled again.
	 */
	private void show(List<Component> wanted)
	{
		for (int i = 0; i < wanted.size(); i++)
		{
			Component card = wanted.get(i);
			if (i >= list.getComponentCount() || list.getComponent(i) != card)
			{
				// Takes the card out of its old place first when it is already in the list
				list.add(card, i);
			}
		}
		while (list.getComponentCount() > wanted.size())
		{
			list.remove(list.getComponentCount() - 1);
		}

		list.revalidate();
		list.repaint();
	}

	private void addHubSection(List<String> terms, Map<String, Card> kept, List<Component> wanted)
	{
		wanted.add(card(kept, "hub-heading", Collections.emptyList(), () ->
		{
			JLabel heading = new JLabel("Custom Model Hub");
			heading.setFont(FontManager.getRunescapeBoldFont());
			heading.setForeground(Color.WHITE);
			heading.setBorder(new EmptyBorder(8, 0, 0, 0));
			return heading;
		}));

		if (!hubEnabled)
		{
			wanted.add(detailCard(kept, "hub-off", "Switch on Enable Custom Model Hub in this plugin's settings to "
				+ "browse and download packs. Until then, nothing is fetched.", ColorScheme.LIGHT_GRAY_COLOR));
			return;
		}
		if (hubLoading)
		{
			wanted.add(detailCard(kept, "hub-loading", "Loading the hub's packs...", ColorScheme.LIGHT_GRAY_COLOR));
			return;
		}
		if (hubError != null)
		{
			wanted.add(detailCard(kept, "hub-error", hubError, ColorScheme.PROGRESS_ERROR_COLOR));
			wanted.add(card(kept, "hub-retry", Collections.emptyList(), () ->
			{
				JButton retry = new JButton("Retry");
				retry.addActionListener(e -> requestHub());
				return retry;
			}));
			return;
		}
		if (hubEntries == null)
		{
			return;
		}

		List<HubEntry> shown = hubEntries.stream()
			.filter(entry -> terms.isEmpty() || matches(entry, terms))
			.collect(Collectors.toList());
		if (shown.isEmpty())
		{
			wanted.add(detailCard(kept, "hub-none", hubEntries.isEmpty() ? "The hub has no packs yet."
				: "No hub packs match.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		for (HubEntry entry : shown)
		{
			// Everything hubItem reads; an entry is the same object until the hub is fetched again
			PackView installed = installedFrom(entry);
			List<Object> shows = Arrays.asList(entry, hubIcons.get(entry.getCommit()), installed != null,
				installed == null ? null : installed.getCommit(), busy.contains(entry.getId()));
			wanted.add(card(kept, "hub:" + entry.getId(), shows, () -> hubItem(entry)));
		}
	}

	private JComponent detailCard(Map<String, Card> kept, String key, String text, Color color)
	{
		return card(kept, key, Arrays.asList(text, color), () -> detail(text, color));
	}

	/** The installed pack a hub entry was installed as, or null. */
	private PackView installedFrom(HubEntry entry)
	{
		return packs.stream()
			.filter(pack -> pack.getId().equals(entry.getPackId()))
			.findFirst().orElse(null);
	}

	/** The folder under {@code hub/} a hub pack is installed in, or null for any other pack. */
	private static String hubFolder(PackView pack)
	{
		return pack.getKind() == PackKind.HUB ? pack.getId().substring(PackKind.HUB.packId("").length()) : null;
	}

	private JPanel hubItem(HubEntry entry)
	{
		JPanel item = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
		item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		item.setBorder(new EmptyBorder(6, 6, 6, 6));

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		ImageIcon icon = hubIcons.get(entry.getCommit());
		if (icon != null)
		{
			top.add(new JLabel(icon), BorderLayout.WEST);
		}
		JLabel name = new JLabel(wrap(entry.getName(), TEXT_WIDTH - (icon != null ? ICON_SIZE + 30 : 30)));
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		top.add(name, BorderLayout.CENTER);

		String repo = entry.getSafeRepo();
		if (repo != null)
		{
			JButton page = new JButton("?");
			page.setToolTipText("Open this pack's page on GitHub");
			page.setMargin(new Insets(0, 4, 0, 4));
			page.addActionListener(e -> LinkBrowser.browse(repo));
			top.add(page, BorderLayout.EAST);
		}
		item.add(top);

		StringBuilder about = new StringBuilder("Hub");
		if (entry.getAuthor() != null && !entry.getAuthor().isBlank())
		{
			about.append(" · by ").append(entry.getAuthor().trim());
		}
		if (entry.getVersion() != null && !entry.getVersion().isBlank())
		{
			about.append(" · v").append(entry.getVersion().trim());
		}
		item.add(detail(about.toString(), ColorScheme.LIGHT_GRAY_COLOR));
		if (entry.getDescription() != null && !entry.getDescription().isBlank())
		{
			item.add(detail(entry.getDescription(), ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (!entry.getModels().isEmpty())
		{
			item.add(detail(entry.getModels().size() + " model" + (entry.getModels().size() == 1 ? "" : "s") + ": "
				+ entry.getModels().stream().map(HubEntry.Model::getName).collect(Collectors.joining(", ")),
				ColorScheme.LIGHT_GRAY_COLOR));
		}

		PackView installed = installedFrom(entry);
		boolean update = installed != null && !entry.getCommit().equals(installed.getCommit());

		if (!entry.isCompatible())
		{
			item.add(detail("Built for a different version of this plugin, so it can't be installed. "
				+ "Updating the plugin may fix this.", ColorScheme.PROGRESS_ERROR_COLOR));
		}

		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setOpaque(false);
		boolean working = busy.contains(entry.getId());
		if (installed == null || update)
		{
			JButton install = new JButton(working ? "Working..." : update ? "Update" : "Install");
			install.setEnabled(!working && entry.isCompatible());
			install.addActionListener(e -> actions.installHubPack(entry));
			buttons.add(install);
		}
		if (installed != null)
		{
			buttons.add(hubRemoveButton(entry.getId(), entry.getName()));
		}
		item.add(buttons);
		return item;
	}

	private JButton hubRemoveButton(String folder, String name)
	{
		return removeButton(busy.contains(folder), "Remove '" + name + "'? You can install it again from the hub.",
			() -> actions.removeHubPack(folder));
	}

	/**
	 * A Remove button that asks first. While {@code working} it says so and can't be pressed again.
	 */
	private JButton removeButton(boolean working, String question, Runnable remove)
	{
		JButton button = new JButton(working ? "Working..." : "Remove");
		button.setEnabled(!working);
		button.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this, question, "Remove pack", JOptionPane.OK_CANCEL_OPTION);
			if (answer == JOptionPane.OK_OPTION)
			{
				remove.run();
			}
		});
		return button;
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

		// A hub pack shows the icon its hub entry was listed with, when the hub has been fetched
		ImageIcon icon = pack.getCommit() == null ? null : hubIcons.get(pack.getCommit());
		JLabel name = new JLabel(wrap(pack.getName(), TEXT_WIDTH - 60 - (icon != null ? ICON_SIZE + 4 : 0)));
		if (icon != null)
		{
			name.setIcon(icon);
			name.setIconTextGap(4);
		}
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(pack.isEnabled() && readable ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		top.add(name, BorderLayout.CENTER);

		int index = packs.indexOf(pack);
		JPanel order = new JPanel(new GridLayout(1, 2, 2, 0));
		order.setOpaque(false);
		// Moved by id rather than by this index: a kept card stays while the packs around it move, so
		// its index can change under it
		order.add(orderButton("▲", "Take priority over the pack above", index > 0, () -> movePack(pack.getId(), -1)));
		order.add(orderButton("▼", "Give priority to the pack below", index < packs.size() - 1,
			() -> movePack(pack.getId(), 1)));
		top.add(order, BorderLayout.EAST);
		item.add(top);

		item.add(detail(describe(pack), ColorScheme.LIGHT_GRAY_COLOR));
		if (pack.getDescription() != null && !pack.getDescription().isBlank())
		{
			item.add(detail(pack.getDescription(), ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (!readable)
		{
			item.add(detail("Could not be read: " + pack.getError(), ColorScheme.PROGRESS_ERROR_COLOR));
			addRemove(item, pack);
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
		addRemove(item, pack);
		return item;
	}

	/**
	 * A hub or local pack can be removed from its own card - a hub pack even while the hub is
	 * switched off, and either one when it can't be read. A local pack is busy under its whole pack
	 * id, so it never shares a state with a hub entry whose folder has the same name.
	 */
	private void addRemove(JPanel item, PackView pack)
	{
		if (pack.getKind() == PackKind.HUB)
		{
			item.add(hubRemoveButton(hubFolder(pack), pack.getName()));
		}
		else if (pack.getKind() == PackKind.LOCAL)
		{
			String folder = pack.getId().substring(PackKind.LOCAL.packId("").length());
			item.add(removeButton(busy.contains(pack.getId()), "Delete '" + pack.getName() + "' from your local "
				+ "packs folder? Its files are deleted; import the pack again to get it back.",
				() -> actions.removeLocalPack(folder)));
		}
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

	private void movePack(String packId, int delta)
	{
		List<String> ids = packs.stream().map(PackView::getId).collect(Collectors.toList());
		int index = ids.indexOf(packId);
		if (index < 0 || index + delta < 0 || index + delta >= ids.size())
		{
			return;
		}
		Collections.swap(ids, index, index + delta);
		actions.setOrder(ids);
	}

	private static JButton orderButton(String text, String tooltip, boolean enabled, Runnable onClick)
	{
		JButton button = new JButton(text);
		button.setToolTipText(tooltip);
		button.setEnabled(enabled);
		button.setFont(button.getFont().deriveFont(Font.PLAIN, 10f));
		button.setMargin(new Insets(0, 2, 0, 2));
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
		if (pack.getAuthor() != null && !pack.getAuthor().isBlank())
		{
			text.append(" · by ").append(pack.getAuthor().trim());
		}
		if (pack.getVersion() != null && !pack.getVersion().isBlank())
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

	/** Every term found in the hub pack's name, author, description or tags, or its models' names. */
	private static boolean matches(HubEntry entry, List<String> terms)
	{
		StringBuilder haystack = new StringBuilder("hub ").append(entry.getName());
		for (String part : new String[]{entry.getAuthor(), entry.getDescription()})
		{
			if (part != null)
			{
				haystack.append(' ').append(part);
			}
		}
		entry.getTags().forEach(tag -> haystack.append(' ').append(tag));
		entry.getModels().forEach(model -> haystack.append(' ').append(model.getName()));
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
