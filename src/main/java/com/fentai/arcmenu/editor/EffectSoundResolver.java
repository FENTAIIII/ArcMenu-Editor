package com.fentai.arcmenu.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/** Resolves one stable file variant so scrubbing never changes the selected sound. */
final class EffectSoundResolver {
    interface Resources {
        /** JSON documents in ascending resource-pack priority. */
        List<String> soundDefinitions(String namespace) throws IOException;
        boolean exists(String resource);
    }
    record Resolved(String resourceId, double baseVolume, double basePitch) {}
    private record Variant(String name, boolean event, int weight, double volume, double pitch, int order) {}
    private final Resources resources;
    private final Map<String, Map<String, List<Variant>>> definitions = new HashMap<>();

    EffectSoundResolver(Resources resources) { this.resources = resources; }

    synchronized Resolved resolve(String kind, String source) throws IOException {
        if (!"sound".equals(kind)) throw new IOException("Only sound cues have decoded OGG audio");
        if (source == null || source.isBlank()) throw new IOException("Sound source is empty");
        String input = source.trim();
        if (input.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            String resource;
            if (input.indexOf(':') >= 0) {
                String id = identifier(input);
                int colon = id.indexOf(':');
                String path = id.substring(colon + 1);
                resource = id.substring(0, colon + 1) + (path.startsWith("sounds/") ? path : "sounds/" + path);
            } else resource = "arcmenu:sounds/effect/" + assetPath(input);
            if (!resources.exists(resource)) throw new IOException("Sound file is missing from the loaded resource pack: " + resource);
            return new Resolved(resource, 1, 1);
        }
        String event;
        if (input.indexOf(':') >= 0) event = identifier(input);
        else {
            String normalized = input.toLowerCase(Locale.ROOT);
            String found = namespace("minecraft").keySet().stream()
                .filter(key -> key.replace('.', '_').equals(normalized)).sorted().findFirst().orElse(null);
            event = "minecraft:" + (found == null ? normalized : found);
            identifier(event);
        }
        return event(event, new HashSet<>(), 1, 1);
    }

    private Resolved event(String id, Set<String> visiting, double volume, double pitch) throws IOException {
        if (visiting.size() >= 32 || !visiting.add(id)) throw new IOException("Sound event references form a cycle: " + id);
        try {
            int colon = id.indexOf(':');
            String namespace = id.substring(0, colon), path = id.substring(colon + 1);
            List<Variant> variants = namespace(namespace).getOrDefault(path, List.of());
            IOException failure = null;
            for (Variant variant : variants.stream().sorted(Comparator.comparingInt(Variant::weight).reversed().thenComparingInt(Variant::order)).toList()) {
                String name = identifier(variant.name.indexOf(':') >= 0 ? variant.name : namespace + ":" + variant.name);
                try {
                    if (variant.event) return event(name, visiting, volume * variant.volume, pitch * variant.pitch);
                    String file = name.substring(0, name.indexOf(':') + 1) + "sounds/" + name.substring(name.indexOf(':') + 1);
                    if (!file.endsWith(".ogg")) file += ".ogg";
                    if (resources.exists(file)) return new Resolved(file, volume * variant.volume, pitch * variant.pitch);
                } catch (IOException error) { failure = error; }
            }
            // Explicit file keys also work without a corresponding sounds.json event.
            String direct = namespace + ":sounds/" + path + (path.endsWith(".ogg") ? "" : ".ogg");
            if (variants.isEmpty() && resources.exists(direct)) return new Resolved(direct, volume, pitch);
            throw new IOException("No playable resource-pack file for sound event " + id, failure);
        } finally { visiting.remove(id); }
    }

    private Map<String, List<Variant>> namespace(String namespace) throws IOException {
        Map<String, List<Variant>> cached = definitions.get(namespace);
        if (cached != null) return cached;
        Map<String, List<Variant>> result = new HashMap<>();
        int order = 0;
        try {
            for (String document : resources.soundDefinitions(namespace)) {
                JsonObject root = JsonParser.parseString(document).getAsJsonObject();
                for (var entry : root.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject event = entry.getValue().getAsJsonObject();
                    List<Variant> variants = event.has("replace") && event.get("replace").getAsBoolean()
                        ? new ArrayList<>() : new ArrayList<>(result.getOrDefault(entry.getKey(), List.of()));
                    if (event.has("sounds") && event.get("sounds").isJsonArray()) {
                        for (JsonElement sound : event.getAsJsonArray("sounds")) {
                            if (sound.isJsonPrimitive() && sound.getAsJsonPrimitive().isString())
                                variants.add(new Variant(sound.getAsString(), false, 1, 1, 1, order++));
                            else if (sound.isJsonObject()) {
                                JsonObject object = sound.getAsJsonObject();
                                if (!object.has("name")) continue;
                                int weight = object.has("weight") ? object.get("weight").getAsInt() : 1;
                                if (weight <= 0) continue;
                                variants.add(new Variant(object.get("name").getAsString(), object.has("type") && "event".equals(object.get("type").getAsString()),
                                    weight, scalar(object.get("volume"), false), scalar(object.get("pitch"), true), order++));
                            }
                        }
                    }
                    result.put(entry.getKey(), List.copyOf(variants));
                }
            }
        } catch (RuntimeException error) { throw new IOException("Invalid sounds.json for namespace " + namespace, error); }
        definitions.put(namespace, result);
        return result;
    }

    private static double scalar(JsonElement value, boolean positive) {
        double result = 1;
        if (value != null && value.isJsonPrimitive()) result = value.getAsDouble();
        else if (value != null && value.isJsonObject()) {
            JsonObject range = value.getAsJsonObject();
            JsonElement min = range.has("min") ? range.get("min") : range.get("min_inclusive");
            JsonElement max = range.has("max") ? range.get("max") : range.get("max_inclusive");
            if (min != null && max != null) result = (min.getAsDouble() + max.getAsDouble()) * .5;
        }
        if (!Double.isFinite(result) || result < 0 || positive && result <= 0) throw new IllegalArgumentException("Invalid sound volume or pitch");
        return result;
    }

    static String identifier(String input) throws IOException {
        if (!input.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || unsafeSegments(input.substring(input.indexOf(':') + 1)))
            throw new IOException("Invalid sound resource identifier: " + input);
        return input;
    }

    static String assetPath(String input) throws IOException {
        String path = input.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (!path.matches("[a-z0-9_./-]+") || unsafeSegments(path))
            throw new IOException("Invalid Effect audio asset path: " + input);
        return path;
    }
    private static boolean unsafeSegments(String path) {
        return Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
    }
}
