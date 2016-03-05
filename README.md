# schedn

An attempt to express (prismatic) schemas (and therefore validation configurations) in pure EDN intuitively & conveniently.

## Motivation
First of all Schema is great! It allows one to specify a 'validator' which looks, more or less, the same as the thing it will eventually be summoned to validate. This property is basically what separates Schema from the 'others' (for better or for worse is a completely separate discussion). So `schedn` takes the view that transparency is good and wants to keep it. So, why not write raw Schemas then? Well, let's consider an example:

```clj
(def Transaction {:cardholder {:last-name s/Str 
                               (s/optional-key :first-name) s/Str}
                  :amount {:value Double  
                           :currency-code s/Str}            
                  :card {(optional-key :pan) S/Str
                         (optional-key :expiry-date) S/Str}             
                  :token s/Str
                  })

```

OK, so what do we have here? First of all, this is NOT all data. It's actually a mixture of code + data. Secondly, there is quite a bit of clutter and room for error when writing deeply nested Schemas. In fact, the above Schema shown has a semantic error.  It marks all keys under :card as optional, but :card itself is mandatory! This means that in theory we would allow a :card entry with a completely empty map! The same sort of thing could have happened for :cardholder entry. In general, when one writes a nested Schema by hand, he/she needs to manually figure out and keep track of which keys **cannot** be optional (because there is mandatory child somewhere downstream), and which ones **must** be optional (because all their children are optional). There is clearly room for error in this exercise. Would it not be great to be able to write something like this instead?
 
```clj
 (def Transaction {[[:cardholder :last-name] :mandatory] 'schema.core/Str
                   [[:cardholder :first-name] :optional] 'schema.core/Str
                   [[:amount :value] :mandatory] 'schema.core/Double
                   [[:amount :currency-code] :mandatory] 'schema.core/Str
                   [[:card :pan] :optional] 'schema.core/Str
                   [[:card :expiry-date] :optional] 'schema.core/Str
                   [[:token] :mandatory] 'schema.core/Str
                   })

```

We now have an opportunity to correctly generate all the keys prior to the leaf nodes (for which we have a presence indicator anyway). We've also uncluttered the whole thing without losing any structural information (the nesting points are clearly visible). I'd argue that this is more compact but yet, more readable and evident. Moreover, one can imagine trivial code to manipulate the above 'template' (e.g. inverting certain presence indicators or removing certain entries). 
  

## Terminology

`schedn` introduces 4 words to describe 4 very specific things:

1. By **schedn-template** we are going to refer to the a map full of schedn-entries
2. By **schedn-entry** we are going to refer to a tuple of `[ [path-to-leaf presence-indicator] leaf-validator ]`
3. By **leaf-validator** we are going to refer to a symbol which must resolve to a schema (to be used against a leaf value)
4. By **validation-configuration** we are going to refer to a map which, at the very least, contains a `:templates` key, which is a map whose values are all schedn-templates

For example, the following map is a **schedn-template**:

```clj
{[[:a :b] :mandatory] 'my.awesome.schemas/UUID-str
 [[:d :e] :optional]  'my.awesome.schemas/PosDouble
 [[:k :j] :mandatory] 'my.awesome.schemas/RFC-3339-date-str}                     

```

## Usage

Usage is mainly centered around a single function `schedn.core/template->schema`. As the name suggests, you can call this on a schedn-template and you will get back a Schema. There are a couple of helpers to manipulate the template prior to producing the schema too (e.g. `remove-template-entries` & `override-template-statuses`). OK, so that's simple enough. Let's now see what more we can do.    
   
We mentioned earlier the notion of a 'validation-configuration'. This is basically a collection of templates. The reason for even using a validation-configuraiton over raw templates is to group the templates under a single entity, to facilitate the notion of **identification-comes-first**, and to offer an opportunity to refine (via `s/constrained`) the master schema that will be produced (by merging all the templates). Let's see a full example:


```clj
{:classifier {:match ["request" "sale" "ssl"]
              :fragments ["request" [:a :b :c] [:a :c :y]]}
 :schema-constraints {:on-self ['schedn.core-test/d-AND-x]}
 :templates {:X {[[:a :b :c] :mandatory] s/Str
                 [[:a :b :d] :optional] s/Str}
            {:Y  [[:a :c :x] :conditional] s/Str
                 [[:a :c :y] :mandatory] s/Str }}}


```

Let's examine the `classifier` entry. The :match key corresponds to what the incoming message's (the message we're currently validating) identifier should be. The identifier is produced by extracting all the path-vectors found in :fragments from the incoming message. An optional prefix is supported as well (i.e. 'request' above). In other words, if the thing we're validating doesn't have value 'sale' under [:a :b :c] AND 'ssl' under [:a :c :x], identification will fail, and no further validation will occur. *This feature is completely optional, and can be turned off by simply not supplying a :classifier entry.*


The `schema-constraints` entry is a bit more involved. The constraints specified in its children apply to the top level schema as produced by merging all the templates and calling `template->schema` on it. It supports 2 keys and 2 keys only. These are **:on-self** & **:on-other** and they can coexist. They both have the same format but slightly different semantics. The format is essentially a vector of namespaced symbols. These symbols must resolve to functions which accept 1 argument if they are under `:on-self`, and 2 arguments if they are under `:on-other`. The master schema is refined sequentially for all resolved functions via `schema.core/constrained`. *This feature is completely optional, and can be turned off by simply not supplying a :schema-constraints entry.*


### on-self
This basically means that the refinements listed need access to the thing we're currently validating (hence they must expect 1 argument). 

### on-other
By the same token, this means  that the refinements listed need access to something external (e.g. some other map). Hence they must expect 2 arguments  - both self AND the-other.

### with-dependent-validation
`with-dependent-validation` is a simple macro which abstracts away the execution flow when validating across 2 things. Let's look at a concrete example to make things clearer. Suppose you have some sort of request arriving from the outside world, and you want to produce a response for it. Obviously, you also want to validate the request as it's coming in, and the response as it's going out. Now, also suppose that validation of the response needs to inspect the request (e.g. if there is a credit card number in the request we may want to produce a response which has a mandatory :luhn-pass? key). So generally, whenever you have 2 things (X & Y) to validate, and validating the Y somehow depends on X, you can use the following code: 

```clj
(with-dependent-validation X x-config y-config & body-producing-Y)
```

If an expression like the above returns an actual value, you can be sure that the X passed validation specified in x-config, and that the Y returned passed validation specified in y-config.  

In case it's not clear from the above, if the validation-config for the response depends on the request, it must specify schema-constraints `:on-other`, and the 'other' to be used is the request. So, in this case, any functions specified under `:on-other` should take the response as the 1st arg (self) and the request as the 2nd (other).

### validate-data-against-schema
`validate-data-against-schema` is a drop-in replacement for `schema.core/validate`. The only reason to use it over `schema.core/validate`, would be because you want to integrate certain coercions (see `*default-coercions*` dynamic Var), and/or you want to have a say in cases where there is more stuff in the map than what has been defined in the schema (by default schema doesn't allow extra stuff!). The way you do that is by supplying your own `react-for-extra!` argument (a function) to `validate-data-against-schema`. The default function for this does a string similarity test across the keys and tries to suggest potential hints (based on edit-distance). For example, if you schema specifies a :port key and you accidentally provide a :pport one, you will get a `println` saying *"Did you mean [:port]?"*


## Worth noting

As mentioned in the 'Motivation' section, the schedn interpreter will infer the presence indicator of any non-leaf keys, by using all the mandatory paths as a guard. This essentially means that you cannot have a leaf key specified as :mandatory, while some of its parents are declared :optional. Similarly, you cannot have some parent key, whose children are all :optional, declared as :mandatory. In other words, mandatory parents must have at least 1 mandatory child. 

Even though, the things you can't have are valid Clojure data-structures, in my experience they don't make much sense (semantically speaking). In what possible scenario would you demand some inner key to be present, when its own parent is not required? This essentially means that the child itself is really optional. By the same token, in what possible scenario would you demand a parent key to be always present, when all of its children are optional? Wouldn't that mean that the parent is itself really optional? Or to put it differently...What does it mean to provide a key with no data (from the client's perspective)? Well, semantically this means that the key is not actually needed, as it provides no information whatsoever! To that end, `schedn` simply prohibits these two confusing cases. Admittedly, this *could* be a major caveat, depending on the domain applied. In my experience, it's a bad idea to allow for semantic confusion, and it's also quite hard to get this right by hand, in deeply nested maps. So, `schedn` automates this work - you supply only the leaf presence-indicators and the interpreter does the rest.
  

## Limitations
Only for Clojure maps.

## License

Copyright © 2016 Dimitrios Piliouras

Distributed under the Eclipse Public License, the same as Clojure.
