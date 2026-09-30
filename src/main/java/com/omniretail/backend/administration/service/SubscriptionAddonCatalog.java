package com.omniretail.backend.administration.service;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
public final class SubscriptionAddonCatalog {
 private SubscriptionAddonCatalog() {}
 public record Addon(String code, String name, BigDecimal monthlyQuetzales, List<String> capabilities) {}
 public static final List<Addon> ADDONS = List.of(
  new Addon("ecommerce_delivery", "E-commerce + Entregas", new BigDecimal("129.00"), List.of("ecommerce", "delivery")),
  new Addon("advanced_reports", "Reportes avanzados", new BigDecimal("99.00"), List.of("reports.advanced")));
 public static List<String> normalize(List<String> codes) {
  if (codes == null || codes.stream().anyMatch(code -> ADDONS.stream().noneMatch(addon -> addon.code().equals(code)))
    || codes.stream().distinct().count() != codes.size()) throw BusinessException.badRequest("La selección de complementos no es válida.");
  return codes.stream().sorted().toList();
 }
 public static List<String> capabilities(List<String> codes) {
  if (codes == null) return List.of();
  return ADDONS.stream().filter(addon -> codes.contains(addon.code())).flatMap(addon -> addon.capabilities().stream()).distinct().toList();
 }
}
