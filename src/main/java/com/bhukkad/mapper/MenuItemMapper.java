package com.bhukkad.mapper;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.storage.MenuImageService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class MenuItemMapper {

    @Autowired
    protected MenuImageService menuImageService;

    @Mapping(source = "category.name", target = "categoryName")
    @Mapping(target = "foodType", expression = "java(enumName(menuItem.getFoodType()))")
    @Mapping(target = "spiceLevel", expression = "java(enumName(menuItem.getSpiceLevel()))")
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "additionalImages", ignore = true)
    @Mapping(target = "customizationOptions", ignore = true)
    public abstract MenuItemResponse toResponse(MenuItem menuItem);

    /**
     * Resolves image URLs via the storage service.  Called from
     * {@link #toResponse(MenuItem)} — MapStruct 1.5.5 ignores
     * {@code @AfterMapping} on abstract-mapper lifecycle methods,
     * so the resolution is invoked explicitly here.
     */
    public MenuItemResponse resolveImageUrls(MenuItem source, MenuItemResponse target) {
        target.setImageUrl(menuImageService.resolvePublicUrl(source.getImageUrl()));
        if (source.getAdditionalImages() != null) {
            target.setAdditionalImages(source.getAdditionalImages().stream()
                    .map(menuImageService::resolvePublicUrl)
                    .collect(Collectors.toList()));
        }
        return target;
    }

    protected String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }
}
