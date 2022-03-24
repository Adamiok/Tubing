package be.garagepoort.mcioc.gui.templates.xml;

import be.garagepoort.mcioc.IocBean;
import be.garagepoort.mcioc.gui.exceptions.TubingGuiException;
import be.garagepoort.mcioc.gui.model.TubingGui;
import be.garagepoort.mcioc.gui.model.TubingGuiItem;
import be.garagepoort.mcioc.gui.model.TubingGuiItemStack;
import be.garagepoort.mcioc.gui.model.TubingGuiText;
import be.garagepoort.mcioc.gui.model.TubingGuiTextPart;
import be.garagepoort.mcioc.gui.templates.xml.style.StyleId;
import be.garagepoort.mcioc.gui.templates.xml.style.TubingGuiTextPartStyleParser;
import be.garagepoort.mcioc.permissions.TubingPermissionService;
import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@IocBean
public class TubingXmlToTubingGuiMapper {
    private static final String IF_ATTR = "if";
    private static final String TEXT_TAG = "t";
    private static final String ON_LEFT_CLICK_ATTR = "onLeftClick";
    private static final String ON_RIGHT_CLICK_ATTR = "onRightClick";
    private static final String ON_MIDDLE_CLICK_ATTR = "onMiddleClick";
    private static final String SLOT_ATTR = "slot";
    private static final String ID_ATTR = "id";
    private static final String COLOR_ATTR = "color";
    private static final String MATERIAL_ATTR = "material";
    private static final String NAME_ELEMENT = "name";
    private static final String NAME_ATTR = "name";
    private static final String ENCHANTED_ATTR = "enchanted";
    private static final String TRUE = "true";
    private static final String PERMISSION_ATTR = "permission";
    public static final String CLASS_ATTR = "class";

    private final TubingPermissionService tubingPermissionService;

    public TubingXmlToTubingGuiMapper(TubingPermissionService tubingPermissionService, TubingGuiTextPartStyleParser tubingGuiTextPartStyleParser) {
        this.tubingPermissionService = tubingPermissionService;
    }

    public TubingGui toTubingGui(Player player, String html) {
        Document document = Jsoup.parse(html);
        Element tubingGuiElement = document.selectFirst("TubingGui");

        if (tubingGuiElement == null) {
            throw new TubingGuiException("Invalid html template. No TubingGui element found");
        }

        int size = StringUtils.isBlank(tubingGuiElement.attr("size")) ? 54 : Integer.parseInt(tubingGuiElement.attr("size"));
        Element titleElement = tubingGuiElement.selectFirst("title");
        TubingGuiText titleGuiText = parseTextElement(titleElement);

        StyleId guiId = getId(tubingGuiElement, true);
        TubingGui.Builder builder = new TubingGui.Builder(guiId, titleGuiText, size);

        Elements guiItems = tubingGuiElement.select("GuiItem");
        for (Element guiItem : guiItems) {
            if (validateShowElement(guiItem, player)) {
                String leftClickAction = guiItem.attr(ON_LEFT_CLICK_ATTR);
                String rightClickAction = guiItem.attr(ON_RIGHT_CLICK_ATTR);
                String middleClickAction = guiItem.attr(ON_MIDDLE_CLICK_ATTR);
                StyleId guiItemId = getId(guiItem, true);

                int slot = Integer.parseInt(guiItem.attr(SLOT_ATTR));
                String material = guiItem.attr(MATERIAL_ATTR);
                Element nameElement = guiItem.selectFirst(NAME_ELEMENT);
                TubingGuiText tubingGuiText = parseItemName(guiItem, nameElement);

                boolean enchanted = guiItem.hasAttr(ENCHANTED_ATTR);
                List<TubingGuiText> loreLines = parseLoreLines(player, guiItem);

                TubingGuiItemStack itemStack = new TubingGuiItemStack(Material.valueOf(material), tubingGuiText, enchanted, loreLines);
                TubingGuiItem tubingGuiItem = new TubingGuiItem.Builder(guiItemId, slot)
                        .withLeftClickAction(leftClickAction)
                        .withRightClickAction(rightClickAction)
                        .withMiddleClickAction(middleClickAction)
                        .withItemStack(itemStack)
                        .build();
                builder.addItem(tubingGuiItem);
            }
        }

        return builder.build();
    }

    private TubingGuiText parseItemName(Element guiItem, Element nameElement) {
        TubingGuiText tubingGuiText;
        if (nameElement != null) {
            tubingGuiText = parseTextElement(nameElement);
        } else {
            String name = "Not configured";
            if (guiItem.hasAttr(NAME_ATTR)) {
                name = guiItem.attr(NAME_ATTR);
            }
            tubingGuiText = new TubingGuiText();
            tubingGuiText.addPart(new TubingGuiTextPart(name, null));
        }
        return tubingGuiText;
    }

    private StyleId getId(Element element, boolean skipIfNoId) {
        if (!hasStyleId(element) && skipIfNoId) {
            return null;
        } else if (!hasStyleId(element) && element.hasParent()) {
            return getId(element.parent(), false);
        } else if (hasStyleId(element)) {
            StyleId parentId = element.hasParent() ? getId(element.parent(), false) : null;
            return buildStyleConfig(element, parentId);
        }
        return null;
    }

    private boolean hasStyleId(Element element) {
        return element.hasAttr(CLASS_ATTR) || element.hasAttr(ID_ATTR);
    }

    private StyleId buildStyleConfig(Element element, StyleId parentId) {
        List<String> classes = new ArrayList<>();
        if (element.hasAttr(CLASS_ATTR)) {
            classes = Arrays.asList(element.attr(CLASS_ATTR).split(" "));
        }

        if (element.hasAttr(ID_ATTR)) {
            return new StyleId(parentId, element.attr("id"), classes);
        } else {
            return new StyleId(parentId, null, classes);
        }
    }

    private List<TubingGuiText> parseLoreLines(Player player, Element guiItem) {
        Element loreElement = guiItem.selectFirst("Lore");
        List<TubingGuiText> loreLines = new ArrayList<>();
        if (loreElement != null) {
            if (validateShowElement(loreElement, player)) {
                List<Element> loreLinesElements = loreElement.select("LoreLine").stream()
                        .filter(g -> validateShowElement(g, player))
                        .collect(Collectors.toList());

                loreLines = loreLinesElements.stream()
                        .map(this::parseTextElement)
                        .collect(Collectors.toList());
            }
        }
        return loreLines;
    }

    private TubingGuiText parseTextElement(Element textElement) {
        if (textElement == null) {
            return new TubingGuiText();
        }

        TubingGuiText itemStackLoreLine = new TubingGuiText();
        itemStackLoreLine.setColor(textElement.attr(COLOR_ATTR));
        if (textElement.select(TEXT_TAG).isEmpty()) {
            TubingGuiTextPart part = new TubingGuiTextPart(textElement.text(), textElement.attr(COLOR_ATTR));
            part.setId(getId(textElement, true));
            itemStackLoreLine.addPart(part);
            return itemStackLoreLine;
        }

        for (Element textPart : textElement.select(TEXT_TAG)) {
            TubingGuiTextPart tubingGuiTextPart = new TubingGuiTextPart(textPart.wholeText(), textPart.attr(COLOR_ATTR));
            tubingGuiTextPart.setId(getId(textPart, true));
            itemStackLoreLine.addPart(tubingGuiTextPart);
        }
        return itemStackLoreLine;
    }

    private boolean validateShowElement(Element guiItem, Player player) {
        return ifCheck(guiItem.attr(IF_ATTR)) && permissionCheck(player, guiItem.attr(PERMISSION_ATTR));
    }

    private boolean ifCheck(String attr) {
        return StringUtils.isBlank(attr) || TRUE.equalsIgnoreCase(attr);
    }

    private boolean permissionCheck(Player player, String attr) {
        return StringUtils.isBlank(attr) || tubingPermissionService.has(player, attr);
    }

    private String format(String string) {
        return ChatColor.translateAlternateColorCodes('&', string);
    }

}
