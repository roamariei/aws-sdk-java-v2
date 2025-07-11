/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.codegen.model.intermediate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class VariableModel extends DocumentationModel {

    private String variableName;

    private String variableType;

    /**
     * Variable declaration type, which can be different from the
     * {@link #variableType}, for example, for auto construct list or map.
     * Otherwise, it's the same as the {@link #variableType}.
     */
    private String variableDeclarationType;

    public VariableModel() {
    }

    public VariableModel(String variableName, String variableType) {
        this(variableName, variableType, variableType);
    }

    public VariableModel(String variableName,
                         String variableType,
                         String variableDeclarationType) {
        setVariableName(variableName);
        setVariableType(variableType);
        setVariableDeclarationType(variableDeclarationType);
    }

    public String getVariableName() {
        return variableName;
    }

    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    public String getVariableType() {
        return variableType;
    }

    public void setVariableType(String variableType) {
        this.variableType = variableType;
    }

    public String getSimpleType() {
        if (variableType.contains(".")) {
            return variableType.substring(variableType.lastIndexOf('.') + 1);
        }
        return variableType;
    }

    public VariableModel withDocumentation(String documentation) {
        setDocumentation(documentation);
        return this;
    }

    public String getVariableDeclarationType() {
        return variableDeclarationType;
    }

    public void setVariableDeclarationType(String variableDeclarationType) {
        this.variableDeclarationType = variableDeclarationType;
    }

    /**
     * Returns the Java type used for the input parameter of a setter method.
     */
    public String getVariableSetterType() {
        String prefix = List.class.getName();
        if (variableType.startsWith(prefix)) {
            return Collection.class.getName() + variableType.substring(prefix.length());
        } else {
            return variableType;
        }
    }

    @Override
    public String toString() {
        return variableName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        VariableModel that = (VariableModel) o;
        return Objects.equals(variableName, that.variableName)
               && Objects.equals(variableType, that.variableType)
               && Objects.equals(variableDeclarationType, that.variableDeclarationType);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(variableName);
        result = 31 * result + Objects.hashCode(variableType);
        result = 31 * result + Objects.hashCode(variableDeclarationType);
        return result;
    }
}
