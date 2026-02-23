package com.github.liyibo1110.jdk.java.util;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * 集合层次结构中的root接口，集合表示一组对象，称为元素，某些集合允许重复元素，而另一些则不允许。
 * 有些集合是有序的，有些是无序的，JDK并未直接实现此接口：它提供了更具体的子接口（例如Set和List）的实现。
 * 该接口通常用于在需要最大通用性的场景下传递和操作集合。
 *
 * 集合应直接实现此接口。
 *
 * 所有通用集合实现类（通常通过其子接口间接实现Collection接口）都应提供两个“标准构造器”：
 * 1、一个无参数的void构造方法，用于创建空集合。
 * 2、一个带单个Collection类型参数的构造方法，用于创建与参数集合元素相同的全新集合。
 * 后者实质上允许用户复制任意集合，生成目标实现类型的等效集合。
 * 虽然无法强制执行此约定（因为接口不能包含构造方法），但Java平台库中的所有通用集合实现均遵循此规范。
 *
 * 某些方法被指定为可选操作，若集合实现未实现特定操作，则应定义相应方法抛出UnsupportedOperationException，
 * 此类方法在集合接口的方法规范中标记为“可选操作”。
 *
 * 某些集合实现对其可包含的元素存在限制，例如部分实现禁止空元素，另一些类型则没有限制。
 * 尝试添加不符合条件的元素将抛出未检查异常，尝试添加不符合条件的元素将抛出未检查异常，通常为空指针异常或类型转换异常。
 * 查询不符合条件元素的存在性可能抛出异常，也可能直接返回false；不同实现可能表现为前者或后者。
 * 更普遍而言，对无效元素执行操作（即使该操作不会导致无效元素被插入集合）时，实现可选择抛出异常或直接成功。
 * 此类异常在接口规范中标记为“可选”。
 *
 * 每个集合应自行确定其同步策略。
 * 若实现未提供更强的保证，当其他线程正在修改集合时调用该集合的任何方法都可能导致未定义行为——这包括直接调用、将集合传递给可能执行调用的方法，以及使用现有迭代器检查集合。
 *
 * 集合框架接口中的许多方法都是基于equals方法定义的。例如，contains(Object o)方法的规范说明：“仅当该集合包含至少一个元素e，且满足(o==null ? e==null : o.equals(e))时返回true。”
 * 该规范不应被解释为：当使用非空参数o调用 Collection.contains 时，会强制对任何元素e调用 o.equals(e)。
 * 实现方可自由采用优化方案避免equals调用，例如通过预先比较两个元素的哈希码来实现。（Object.hashCode()规范保证哈希码不相等的两个对象不可能相等。）
 * 更普遍而言，各类集合框架接口的实现可自由利用底层Object方法的指定行为，具体取决于实现者的判断。
 *
 * 某些执行集合递归遍历的操作，在集合直接或间接包含自身的自引用实例中可能抛出异常。
 * 这包括clone()、equals()、hashCode() 和 toString()方法。
 * 实现可选择性处理自引用场景，但当前多数实现并未如此。
 *
 * View Collections
 * 大多数集合会管理其包含元素的存储空间。相比之下，视图集合本身并不存储元素，而是依赖后端集合来存储实际元素。
 * 视图集合自身无法处理的操作将委托给后端集合执行，视图集合的示例包括由Collections.checkedCollection、Collections.synchronizedCollection和Collections.unmodifiableCollection等方法返回的包装集合。
 * 视图集合的其他示例包括提供相同元素不同表示形式的集合，例如List.subList、NavigableSet.subSet或Map.entrySet所提供的集合。
 * 对后端集合的任何修改都会在视图集合中体现。相应地，若允许修改视图集合，则对视图集合的任何变更都会写入支撑集合。
 * 尽管从技术上讲Iterator和ListIterator并非集合，但它们的实例同样允许修改写入支撑集合，某些情况下支撑集合的变更在迭代过程中对迭代器可见。
 *
 * Unmodifiable Collections
 * 该接口的某些方法被视为“破坏性”方法，称为“修改器”方法，因为它们会修改操作对象所处的集合中包含的对象组。
 * 若该集合实现不支持此操作，可指定这些方法抛出UnsupportedOperationException异常。
 * 当调用对集合无效时，此类方法应（但非必须）抛出UnsupportedOperationException异常。
 * 例如，假设某个集合不支持添加操作。若对此集合调用addAll方法并传入空集合作为参数，由于添加零个元素不会产生任何效果，该集合完全可以不执行任何操作且不抛出异常。
 * 但建议此类情况无条件抛出异常，因为仅在特定情况下抛出异常可能导致编程错误。
 *
 * 不可修改的集合是指其所有修改方法（如上所述）均被指定为抛出UnsupportedOperationException的集合。
 * 因此，此类集合无法通过调用其任何方法进行修改。要使集合真正不可修改，从其派生的所有视图集合也必须不可修改。
 * 例如，若List不可修改，则List.subList返回的子列表同样不可修改。
 *
 * 不可修改集合未必是不可变的。若其包含的元素可变，则整个集合显然具有可变性，即使它本身不可修改。
 * 例如，考虑两个包含可变元素的不可修改列表。若元素发生变动，即使两个列表本身不可修改，调用 list1.equals(list2) 的结果仍可能随调用次数变化。
 * 但若不可修改集合仅包含不可变元素，则可视为实质上不可变。
 *
 * Unmodifiable View Collections
 * 不可修改的视图集合是一种既不可修改、又作为底层集合视图的集合。
 * 其修改方法会抛出如上所述的UnsupportedOperationException异常，而读取和查询方法则委托给底层集合处理。
 * 其效果是为底层集合提供只读访问权限。这有助于组件向用户开放内部集合的读取权限，同时防止用户意外修改这些集合。
 * 不可修改的视图集合示例包括由Collections.unmodifiableCollection、Collections.unmodifiableList及相关方法返回的集合。
 *
 * 需注意：底层集合仍可能被修改，且修改内容将通过不可修改视图显现。
 * 因此不可修改视图集合本身未必不可变。但若其底层集合实质不可变，或底层集合仅通过不可修改视图被引用，则该视图可视为实质不可变。
 *
 * Serializability of Collections
 * 集合的可序列化性是可选的。因此，没有任何集合接口声明实现java.io.Serializable接口。
 * 然而，可序列化性通常被认为具有实用价值，因此大多数集合实现都具备可序列化特性。
 *
 * 对于公开类（如 ArrayList 或 HashMap）的集合实现，若其确实可序列化，则会声明实现Serializable接口。
 * 某些集合实现并非公共类，例如不可修改的集合。此类集合的序列化能力会在创建它们的方法规范或其他合适位置进行说明。
 * 若未明确说明集合的序列化能力，则无法保证其序列化特性。尤其需要注意的是，许多视图集合不具备序列化能力。
 *
 * 实现Serializable接口的集合实现无法保证其可序列化性。原因在于集合通常包含其他类型的元素，而无法静态判断某些元素类型的实例是否真正可序列化。
 * 例如，考虑一个可序列化的Collection<E>，其中E未实现Serializable接口。若该集合仅包含E的某个可序列化子类型的元素，或集合为空时，则该集合可能具有可序列化性。
 * 因此集合被称为条件序列化，因为集合整体的序列化能力取决于集合本身是否可序列化，以及所有包含元素是否同样可序列化。
 *
 * SortedSet和SortedMap的实例存在特殊情况。这些集合可通过Comparator创建，该比较器为集合元素或映射键施加排序规则。
 * 此类集合仅在提供的Comparator同样可序列化时才具备序列化能力。
 *
 * @author liyibo
 * @date 2026-02-23 01:17
 */
public interface Collection<E> extends Iterable<E> {

    // Query Operations

    int size();

    boolean isEmpty();

    /**
     * 若该集合包含指定元素，则返回true。
     * 更正式地说，当且仅当该集合包含至少一个元素e，使得Objects.equals(o, e)为真时，返回true。
     */
    boolean contains(Object o);

    Iterator<E> iterator();

    /**
     * 返回一个包含本集合中所有元素的数组。若本集合对其迭代器返回元素的顺序作出任何保证，则此方法必须按相同顺序返回元素。
     * 返回数组的运行时组件类型为Object。
     *
     * 返回的数组具有“安全”特性，即本集合不会对其保持任何引用（换言之，即使本集合由数组支撑，该方法也必须分配新数组）。
     * 因此调用方可自由修改返回的数组。
     */
    Object[] toArray();

    /**
     * 返回一个包含本集合中所有元素的数组；该数组的运行时类型与指定数组相同。
     * 若本集合能完全容纳于指定数组中，则直接返回该数组。否则，将分配一个具有指定数组运行时类型且大小等同于本集合的新数组。
     * 若本集合能完全容纳于指定数组且仍有剩余空间（即数组元素数量多于本集合），则数组中紧接在集合末尾之后的元素将被设为空值。（此特性仅在调用方确知本集合不含空值元素时，才可用于确定集合长度。）
     * 若该集合对其迭代器返回元素的顺序作出任何保证，则本方法必须按相同顺序返回元素。
     */
    <T> T[] toArray(T[] a);

    default <T> T[] toArray(IntFunction<T[]> generator) {
        return toArray(generator.apply(0));
    }

    // Modification Operations

    /**
     * 确保该集合包含指定元素（可选操作）。若调用导致集合发生变更，则返回true。（若该集合不允许重复元素且已包含指定元素，则返回false。）
     * 支持此操作的集合可能对可添加元素设置限制。具体而言，某些集合会拒绝添加null元素，另一些则会对可添加元素的类型施加限制。集合类应在其文档中明确说明对可添加元素的任何限制。
     * 若集合因除已包含该元素外的其他原因拒绝添加特定元素，则必须抛出异常（而非返回false）。
     * 此举可保持不变量：调用返回后，集合始终包含指定元素。
     */
    boolean add(E e);

    /**
     * 从该集合中移除指定元素的单个实例（若存在，此为可选操作）。
     * 更正式地说，若该集合包含一个或多个满足Objects.equals(o, e) 条件的元素，则移除该元素e。
     * 若该集合曾包含指定元素（或等效地，若该集合因本次调用而发生变化），则返回true。
     */
    boolean remove(Object o);

    // Bulk Operations

    boolean containsAll(Collection<?> c);

    boolean addAll(Collection<? extends E> c);

    boolean removeAll(Collection<?> c);

    default boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        boolean removed = false;
        final Iterator<E> each = iterator();
        while(each.hasNext()) {
            if(filter.test(each.next())) {
                each.remove();
                removed = true;
            }
        }
        return removed;
    }

    boolean retainAll(Collection<?> c);

    /**
     * 可选操作
     */
    void clear();

    // Comparison and hashing

    /**
     * 虽然Collection接口未对Object.equals的通用契约添加任何限制，但若开发者选择直接实现Collection接口（即创建既是Collection又非Set或List的类），则必须谨慎处理Object.equals的重写。
     * 此操作并非强制要求，最简洁的做法是依赖Object的默认实现。但实现者可能希望用“值比较”替代默认的“引用比较”（List和Set接口强制要求此类值比较）。
     * Object.equals方法的通用契约规定：equals必须满足对称性（即a.equals(b)当且仅当b.equals(a)）。
     * 而List.equals和Set.equals的契约则限定列表仅与列表相等，集合仅与集合相等。
     * 因此，对于既不实现List也不实现Set接口的集合类，其自定义equals方法在与任何列表或集合比较时必须返回false。（基于相同逻辑，无法编写同时正确实现Set和List接口的类。）
     */
    boolean equals(Object o);

    /**
     * 返回此集合的哈希码值。
     * 虽然Collection接口未对Object.hashCode方法的通用契约添加任何限制，但程序员应注意：任何重写Object.equals方法的类，也必须重写Object.hashCode方法，以满足Object.hashCode方法的通用契约。
     * 特别地，c1.equals(c2)意味着c1.hashCode()==c2.hashCode()。
     */
    int hashCode();
}
