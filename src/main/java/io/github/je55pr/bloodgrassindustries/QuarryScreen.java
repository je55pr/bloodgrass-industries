package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = BloodgrassIndustries.ID, value = Dist.CLIENT)
public final class QuarryScreen extends AbstractContainerScreen<QuarryMenu> {
    static final String[] LABELS = {"Base Y", "Width", "Drop", "Steps"};
    private final String[] values;
    private final List<QuarryPlan.Corner> corners;
    private QuarryPlan.Settings settings;
    private QuarryMaterials.Finish finish;
    private QuarryMaterials.Report allocationReport;
    private QuarryMaterials.Allocation allocation;
    private QuarryPlan plan;
    private String status = "";
    private boolean dirty = true;
    private Button save;
    private Button excavate;
    private Button preview;

    private QuarryScreen(QuarryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        QuarryPlan.Settings saved = menu.settings;
        finish = menu.finish;
        values = new String[]{Integer.toString(saved.baseY()), Integer.toString(saved.width()),
                Integer.toString(saved.drop()), Integer.toString(saved.steps())};
        corners = new ArrayList<>(saved.corners());
        imageWidth = 232;
        imageHeight = 162;
    }

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(BloodgrassIndustries.QUARRY_MENU.get(), QuarryScreen::new);
    }

    @Override
    protected void init() {
        super.init();
        for (int field = 0; field < values.length; field++) {
            int index = field;
            EditBox input = new EditBox(font, leftPos + 61, topPos + 28 + field * 20, 40, 16, Component.literal(LABELS[field]));
            input.setMaxLength(6);
            input.setFilter(value -> value.matches("-?[0-9]{0,5}"));
            input.setValue(values[field]);
            input.setResponder(value -> {
                values[index] = value;
                dirty = true;
            });
            addRenderableWidget(input);
        }
        preview = button("World preview", 113, 28, 108, () -> QuarryFlyMode.open(this));
        button("Clear outline", 113, 50, 108, () -> {
            corners.clear();
            dirty = true;
        });
        button("Materials", 113, 73, 108, () -> minecraft.setScreen(new QuarryMaterialsScreen(this)));
        save = button("Save", 10, 134, 100, () -> send(false));
        excavate = button("Start excavation", 113, 134, 108, () -> send(true));
    }

    private Button button(String label, int x, int y, int width, Runnable onPress) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> onPress.run())
                .bounds(leftPos + x, topPos + y, width, 18).build());
    }

    QuarryPlan plan() {
        if (!dirty) {
            return plan;
        }
        dirty = false;
        settings = null;
        plan = null;
        try {
            var draft = new QuarryPlan.Settings(Integer.parseInt(values[0]), Integer.parseInt(values[1]),
                    Integer.parseInt(values[2]), Integer.parseInt(values[3]), corners);
            draft.validate(minecraft.level.getMinBuildHeight(), minecraft.level.getMaxBuildHeight());
            settings = draft;
            plan = QuarryPlan.create(draft, minecraft.level.getMinBuildHeight(), minecraft.level.getMaxBuildHeight());
            status = "Lowest bench Y " + plan.deepestY();
        } catch (NumberFormatException incomplete) {
            status = "Enter a number in each field.";
        } catch (IllegalArgumentException invalid) {
            status = invalid.getMessage();
        }
        return plan;
    }

    String status() {
        plan();
        return status;
    }

    String value(int field) {
        return values[field];
    }

    int numericValue(int field) {
        return switch (values[field]) {
            case "", "-" -> field == 0 ? menu.settings.baseY() : 1;
            default -> Integer.parseInt(values[field]);
        };
    }

    boolean setValue(int field, int value) {
        String text = Integer.toString(value);
        if (text.equals(values[field])) {
            return false;
        }
        values[field] = text;
        dirty = true;
        return true;
    }

    List<QuarryPlan.Corner> corners() {
        return corners;
    }

    boolean addCorner(QuarryPlan.Corner corner) {
        if (corners.size() >= QuarryPlan.MAX_CORNERS || corners.contains(corner)) {
            return false;
        }
        corners.add(corner);
        dirty = true;
        return true;
    }

    boolean undoCorner() {
        if (corners.isEmpty()) {
            return false;
        }
        corners.removeLast();
        dirty = true;
        return true;
    }

    private void send(boolean cut) {
        QuarryPlan current = plan();
        if (settings != null && (!cut || current != null && minecraft.player.isCreative())) {
            PacketDistributor.sendToServer(new QuarryPayload(menu.containerId,
                    cut ? QuarryPayload.Action.EXCAVATE : QuarryPayload.Action.SAVE, settings, finish));
        }
    }

    void surveyMaterials() {
        if (plan() != null && minecraft.player.isCreative()) {
            menu.forgetReport(settings);
            menu.materialStatusSettings = settings;
            menu.materialStatus = QuarryBlockEntity.SURVEYING;
            PacketDistributor.sendToServer(new QuarryPayload(menu.containerId, QuarryPayload.Action.SURVEY, settings, finish));
        }
    }

    QuarryMaterials.Report report() {
        plan();
        return settings != null && settings.equals(menu.materialSettings) ? menu.materialReport : null;
    }

    String materialStatus() {
        plan();
        return settings != null && settings.equals(menu.materialStatusSettings) ? menu.materialStatus : "";
    }

    QuarryMaterials.Finish finish() {
        return finish;
    }

    void setFinish(QuarryMaterials.Finish selected) {
        finish = selected;
        allocationReport = null;
        allocation = null;
    }

    QuarryMaterials.Allocation allocation() {
        QuarryMaterials.Report report = report();
        if (allocationReport != report) {
            allocationReport = report;
            allocation = report == null ? null : report.allocate(finish);
        }
        return allocation;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        QuarryPlan current = plan();
        save.active = settings != null;
        preview.active = minecraft.player.isCreative();
        excavate.active = current != null && preview.active;
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff24262a);
        graphics.drawString(font, title, leftPos + 10, topPos + 10, 0xfff3f0ea);
        for (int i = 0; i < LABELS.length; i++) {
            graphics.drawString(font, LABELS[i], leftPos + 10, topPos + 32 + i * 20, 0xffe3e4e6);
        }
        graphics.drawString(font, "Corners " + corners.size(), leftPos + 113, topPos + 96, 0xffe3e4e6);
        String message = !preview.active ? "Quarry flight and excavation require Creative mode."
                : current != null && !finish.isNatural() && report() != null && allocation() == null
                    ? "The last survey found insufficient materials. Excavation will check again."
                : current != null && !finish.isNatural() ? "Materials will be checked before excavation." : status();
        graphics.drawWordWrap(font, Component.literal(message),
                leftPos + 10, topPos + 109, imageWidth - 20, 0xffefbf80);
    }
}
