package autodagger.compiler;

import com.google.auto.common.MoreElements;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

import org.apache.commons.lang3.StringUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import autodagger.AutoComponent;
import autodagger.compiler.utils.AutoComponentClassNameUtil;
import processorworkflow.AbstractComposer;
import processorworkflow.AbstractProcessing;
import processorworkflow.Errors;
import processorworkflow.Logger;
import processorworkflow.ProcessingBuilder;

/**
 * @author Lukasz Piliszczuk - lukasz.pili@gmail.com
 */
public class ComponentProcessing extends AbstractProcessing<ComponentSpec, State> {

    /**
     * Build all extractors first, then build all builders, because
     * we want to gather all targets first
     */
    private final Set<ComponentExtractor> extractors;

    public ComponentProcessing(Elements elements, Types types, Errors errors, State state) {
        super(elements, types, errors, state);
        extractors = new HashSet<>();
    }

    @Override
    public Set<Class<? extends Annotation>> supportedAnnotations() {
        Set set = ImmutableSet.of(AutoComponent.class);
        return set;
    }

    @Override
    protected void processElements(Set<? extends Element> annotationElements) {
        super.processElements(annotationElements);
        if (errors.hasErrors()) {
            return;
        }

        processExtractors();
    }

    @Override
    public boolean processElement(Element element, Errors.ElementErrors elementErrors) {
        if (ElementKind.ANNOTATION_TYPE.equals(element.getKind())) {
            // @AutoComponent is applied on another annotation, find out the targets of that annotation
            Set<? extends Element> targetElements = roundEnvironment.getElementsAnnotatedWith(MoreElements.asType(element));
            for (Element targetElement : targetElements) {
                process(targetElement, element);
                if (errors.hasErrors()) {
                    return false;
                }
            }
            return true;
        }

        process(element, element);

        if (errors.hasErrors()) {
            return false;
        }

        return true;
    }

    private void process(Element targetElement, Element element) {
        Logger.d("Process extractor for %s - %s", targetElement.getSimpleName(), element.getSimpleName());
        ComponentExtractor extractor = new ComponentExtractor(targetElement, element, types, elements, errors);
        if (errors.hasErrors()) {
            return;
        }

        extractors.add(extractor);
        Logger.d("Add target %s", extractor.getTargetTypeMirror());
    }

    private void processExtractors() {
        for (ComponentExtractor extractor : extractors) {
            ComponentSpec spec = new Builder(extractor, errors).build();
            if (errors.hasErrors()) {
                return;
            }

            specs.add(spec);
        }
    }


    @Override
    public AbstractComposer<ComponentSpec> createComposer() {
        return new ComponentComposer(specs);
    }

    private class Builder extends ProcessingBuilder<ComponentExtractor, ComponentSpec> {

        public Builder(ComponentExtractor extractor, Errors errors) {
            super(extractor, errors);
        }

        @Override
        protected ComponentSpec build() {
            ComponentSpec componentSpec = new ComponentSpec(AutoComponentClassNameUtil.getComponentClassName(extractor.getComponentElement()));
            componentSpec.setTargetTypeName(TypeName.get(extractor.getTargetTypeMirror()));

            if (extractor.getScopeAnnotationTypeMirror() != null) {
                componentSpec.setScopeAnnotationSpec(AnnotationSpec.get(extractor.getScopeAnnotationTypeMirror()));
            }

            // injectors
            componentSpec.setInjectorSpecs(getAdditions(state.getInjectorExtractors()));

            // exposed
            componentSpec.setExposeSpecs(getAdditions(state.getExposeExtractors()));

            // dependencies
            componentSpec.setDependenciesTypeNames(getDependencies());

            // superinterfaces
            componentSpec.setSuperinterfacesTypeNames(getTypeNames(extractor.getSuperinterfacesTypeMirrors()));

            // modules
            componentSpec.setModulesTypeNames(getTypeNames(extractor.getModulesTypeMirrors()));

            return componentSpec;
        }

        private List<AdditionSpec> getAdditions(List<AdditionExtractor> extractors) {
            List<AdditionSpec> specs = new ArrayList<>();

            // for each additions
            for (AdditionExtractor additionExtractor : extractors) {
                // for each targets in those additions
                for (TypeMirror typeMirror : additionExtractor.getTargetTypeMirrors()) {
                    // find if that target is a target for the current component
                    // happens only 1 time per loop
                    if (ProcessingUtil.areTypesEqual(extractor.getTargetTypeMirror(), typeMirror)) {
                        // this component is targeted by this addition

                        String name;
                        if (additionExtractor.getProviderMethodName() != null) {
                            name = additionExtractor.getProviderMethodName();

                            // try to remove "provide" or "provides" from name
                            if (StringUtils.startsWith(name, "provides")) {
                                name = StringUtils.removeStart(name, "provides");
                            } else if (StringUtils.startsWith(name, "provide")) {
                                name = StringUtils.removeStart(name, "provide");
                            }
                            name = StringUtils.uncapitalize(name);
                        } else {
                            name = StringUtils.uncapitalize(additionExtractor.getAdditionElement().getSimpleName().toString());
                        }

                        TypeName typeName;
                        ClassName className = ClassName.get(additionExtractor.getAdditionElement());
                        if (additionExtractor.getParameterizedTypeMirrors().isEmpty()) {
                            typeName = className;
                        } else {
                            // with parameterized types
                            TypeName[] types = new TypeName[additionExtractor.getParameterizedTypeMirrors().size()];
                            int i = 0;
                            for (TypeMirror tm : additionExtractor.getParameterizedTypeMirrors()) {
                                types[i++] = TypeName.get(tm);
                            }

                            typeName = ParameterizedTypeName.get(className, types);
                        }

                        AdditionSpec spec = new AdditionSpec(name, typeName);

                        // add qualifier if it exists
                        if (additionExtractor.getQualifierAnnotationMirror() != null) {
                            spec.setQualifierAnnotationSpec(AnnotationSpec.get(additionExtractor.getQualifierAnnotationMirror()));
                        }

                        specs.add(spec);
                    }
                }
            }

            return specs;
        }

        private List<TypeName> getDependencies() {
            List<TypeName> typeNames = new ArrayList<>();
            if (extractor.getDependenciesTypeMirrors() == null) {
                return typeNames;
            }

            mainLoop:
            for (TypeMirror typeMirror : extractor.getDependenciesTypeMirrors()) {
                // check if dependency type mirror references an @AutoComponent target
                // if so, build the TypeName that matches the target component
                for (ComponentExtractor componentExtractor : extractors) {
                    if (componentExtractor == extractor) {
                        // ignore self
                        continue;
                    }

                    if (ProcessingUtil.areTypesEqual(componentExtractor.getTargetTypeMirror(), typeMirror)) {
                        typeNames.add(AutoComponentClassNameUtil.getComponentClassName(componentExtractor.getComponentElement()));
                        continue mainLoop;
                    }
                }

                typeNames.add(TypeName.get(typeMirror));
            }

            return typeNames;
        }

        private List<TypeName> getTypeNames(List<TypeMirror> typeMirrors) {
            List<TypeName> typeNames = new ArrayList<>();
            if (typeMirrors == null) {
                return typeNames;
            }

            for (TypeMirror typeMirror : typeMirrors) {
                typeNames.add(TypeName.get(typeMirror));
            }

            return typeNames;
        }
    }
}
